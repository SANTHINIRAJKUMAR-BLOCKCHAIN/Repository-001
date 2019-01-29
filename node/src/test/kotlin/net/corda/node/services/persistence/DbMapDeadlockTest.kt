package net.corda.node.services.persistence

import net.corda.core.schemas.MappedSchema
import net.corda.core.utilities.contextLogger
import net.corda.node.internal.createCordaPersistence
import net.corda.node.internal.startHikariPool
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.utilities.AppendOnlyPersistentMap
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.nodeapi.internal.persistence.TransactionIsolationLevel
import net.corda.testing.internal.TestingNamedCacheFactory
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.Thread.sleep
import java.sql.SQLException
import java.util.*
import java.util.concurrent.CountDownLatch
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestKey(val value: Int) {
    override fun equals(other: Any?): Boolean {
        return (other as? TestKey)?.value?.equals(value) ?: false
    }

    /**
     * Hash code is constant to provoke hash clashes in ConcurrentHashMap
     */
    override fun hashCode(): Int {
        return 127
    }
}

@Entity
@javax.persistence.Table(name = "locktestobjects")
class MyPersistenceClass(
        @Id
        @Column(name = "lKey", nullable = false)
        val key: Int,

        @Column(name = "lValue", nullable = false)
        val value: Int)


@Entity
@javax.persistence.Table(name = "otherlockobjects")
class SecondPersistenceClass(
        @Id
        @Column(name = "lKey", nullable = false)
        val key: Int,

        @Column(name = "lValue", nullable = false)
        val value: Int)

object LockDbSchema

object LockDbSchemaV2 : MappedSchema(LockDbSchema.javaClass, 2, listOf(MyPersistenceClass::class.java, SecondPersistenceClass::class.java)) {
    override val migrationResource: String? = "locktestschema"
}

class DbMapDeadlockTest {
    companion object {
        val log = contextLogger()
    }

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val sqlServerProperties : Properties
            get() {
                return Properties().also {
                    it.setProperty("dataSourceClassName", "com.microsoft.sqlserver.jdbc.SQLServerDataSource")
                    it.setProperty("dataSource.url", "jdbc:sqlserver://localhost:1433;databaseName=perftesting;encrypt=true;trustServerCertificate=true;hostNameInCertificate=*;loginTimeout=30;sendStringParametersAsUnicode=false")
                    it.setProperty("dataSource.user", "sa")
                    it.setProperty("dataSource.password", "yourStrong(!)Password")
                    it.setProperty("autoCommit", "false")
                }
            }

    private val h2Properties : Properties
    get(){
        return Properties().also {
            it.setProperty("dataSourceClassName", "org.h2.jdbcx.JdbcDataSource")
            it.setProperty("dataSource.url", "jdbc:h2:file:${temporaryFolder.root}/persistence;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;LOCK_TIMEOUT=10000")
            it.setProperty("dataSource.user", "sa")
            it.setProperty("dataSource.password", "")
        }
    }

    @Test
    fun checkAppendOnlyPersistentMapForDeadlockH2(){
        recreateDeadlock(h2Properties)
    }

    // To run this test, run sql server in docker using: docker run -e 'ACCEPT_EULA=Y' -e 'SA_PASSWORD=yourStrong(!)Password' -p 1433:1433 -d microsoft/mssql-server-linux:2017-latest
    // and create a database called 'perftesting' in the db server
    @Ignore("Requires a local SqlServer running, e.g. in a docker container")
    @Test
    fun checkAppendOnlyPersistentMapForDeadlockSqlServer(){
        recreateDeadlock(sqlServerProperties)
    }

    fun recreateDeadlock(hikariProperties: Properties) {
        val cacheFactory = TestingNamedCacheFactory()
        val dbConfig = DatabaseConfig(initialiseSchema = true, transactionIsolationLevel = TransactionIsolationLevel.READ_COMMITTED, runMigration = true)
        val schemaService = NodeSchemaService(extraSchemas = setOf(LockDbSchemaV2))
        createCordaPersistence(dbConfig, { null }, { null }, schemaService, cacheFactory, null).apply {
            startHikariPool(hikariProperties, dbConfig, schemaService.schemaOptions.keys)
        }.use { persistence ->

            // First clean up any remains from previous test runs
            persistence.transaction {
                session.createNativeQuery("delete from locktestobjects").executeUpdate()
                session.createNativeQuery("delete from otherlockobjects").executeUpdate()
            }

            // Prepare a few rows for reading in table 1
            val prepMap = AppendOnlyPersistentMap<TestKey, Int, MyPersistenceClass, Int>(
                    cacheFactory,
                    "myTestCache",
                    { k -> k.value },
                    { e -> Pair(TestKey(e.key), e.value) },
                    { k, v -> MyPersistenceClass(k.value, v) },
                    MyPersistenceClass::class.java
            )

            persistence.transaction {
                prepMap.set(TestKey(1), 1)
                prepMap.set(TestKey(2), 2)
                prepMap.set(TestKey(10), 10)
            }

            // the map that will read from the prepared table
            val testMap = AppendOnlyPersistentMap<TestKey, Int, MyPersistenceClass, Int>(
                    cacheFactory,
                    "myTestCache",
                    { k -> k.value },
                    { e -> Pair(TestKey(e.key), e.value) },
                    { k, v -> MyPersistenceClass(k.value, v) },
                    MyPersistenceClass::class.java
            )

            // a second map that writes to another (unrelated table)
            val otherMap = AppendOnlyPersistentMap<TestKey, Int, SecondPersistenceClass, Int>(
                    cacheFactory,
                    "myTestCache",
                    { k -> k.value },
                    { e -> Pair(TestKey(e.key), e.value) },
                    { k, v -> SecondPersistenceClass(k.value, v) },
                    SecondPersistenceClass::class.java
            )

            val latch = CountDownLatch(1)

            var otherThreadException: Exception? = null

            // This thread will wait for the main thread to do a few things. Then it will starting to read key 2, and write a key to
            // the second table. This read will be buffered (not flushed) at first. The subsequent access to read value 10 fromt the
            // first table will cause the previous write to flush. As the row this will be writing to should be locked from the main
            // thread, it will wait for the main thread's db transaction to commit or rollback before proceeding with the read.
            val otherThread = thread(name = "testThread2") {
                try {
                    log.info("Thread2 waiting")
                    latch.await()
                    log.info("Thread2 starting transaction")
                    persistence.transaction {
                        log.info("Thread2 getting key 2")
                        testMap.get(TestKey(2))
                        log.info("Thread2 set other value 100")
                        otherMap.set(TestKey(100), 100)
                        log.info("Thread2 getting value 10")
                        val v = testMap.get(TestKey(10))
                        assertEquals(10, v)
                    }
                    log.info("Thread2 done")
                } catch (e: Exception) {
                    otherThreadException = e
                }
            }


            log.info("MainThread sleep 200")
            sleep(200)

            // The main thread will write to the same key in the second table, and then read key 1 from the read table. As it will do that
            // before triggering the run on thread 2, it will get the row lock in the second table when flushing before the read, then
            // read and carry on.
            log.info("MainThread starting transaction")
            persistence.transaction {
                log.info("MainThread getting key 2")
                testMap.get(TestKey(2))
                log.info("MainThread set other key 100")
                otherMap.set(TestKey(100), 100)
                log.info("MainThread getting key 1")
                testMap.get(TestKey(1))

                // Then it will trigger the start of the second thread (see above) and then sleep for a bit to make sure the other
                // thread actually runs.
                log.info("MainThread signal")
                latch.countDown()
                log.info("MainThread sleep 2000")
                sleep(2000)

                // finally it will try to get the same value from the read table that the other thread is trying to read.
                // If access to reading this value from the DB is guarded by a lock, the other thread will be holding this lock
                // which means the threads are now deadlocked.
                log.info("MainThread get value 10")
                try {
                    assertEquals(10, testMap.get(TestKey(10)))
                } catch (e: Exception) {
                    checkException(e)
                }
            }
            log.info("MainThread waiting for Thread2")
            otherThread.join()
            checkException(otherThreadException)
            log.info("MainThread done")
        }
    }

    // We have to catch any exception thrown and check what they are - primary key constraint violations are fine, we are trying
    // to insert the same key twice after all. Any deadlock time outs or similar are completely not fine and should be a test failure.
    private fun checkException(exception: Exception?) {
        if (exception == null){
            return
        }
        val persistenceException = exception as? javax.persistence.PersistenceException
        if ( persistenceException!= null ){
            val hibernateException = persistenceException.cause as? org.hibernate.exception.ConstraintViolationException
            if( hibernateException != null ) {
                log.info("Primary key violation exception is fine")
                return
            }
        }
        throw exception
    }
}

