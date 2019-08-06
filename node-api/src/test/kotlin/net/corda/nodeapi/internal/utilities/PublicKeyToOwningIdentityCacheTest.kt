package net.corda.nodeapi.internal.utilities

import junit.framework.TestCase.assertEquals
import net.corda.core.utilities.getOrThrow
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.withoutDatabaseAccess
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.PublicKey
import java.util.*
import java.util.concurrent.Executors

class PublicKeyToOwningIdentityCacheTest {

    private lateinit var database: CordaPersistence
    private lateinit var testCache: PublicKeyToOwningIdentityCache
    private lateinit var services: MockServices
    private val testKeys = mutableListOf<Pair<KeyOwningIdentity, PublicKey>>()
    private val alice = TestIdentity(ALICE_NAME, 20)

    @Before
    fun setUp() {
        val databaseAndServices = MockServices.makeTestDatabaseAndPersistentServices(
                listOf(),
                alice,
                testNetworkParameters(),
                setOf(),
                setOf()
        )
        database = databaseAndServices.first
        services = databaseAndServices.second
        testCache = PublicKeyToOwningIdentityCache(database, 1000)
        createTestKeys()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createTestKeys() {
        val duplicatedUUID = UUID.randomUUID()
        val uuids = listOf(UUID.randomUUID(), UUID.randomUUID(), null, null, duplicatedUUID, duplicatedUUID)
        uuids.forEach {
            val key = if (it != null) {
                services.keyManagementService.freshKey(it)
            } else {
                services.keyManagementService.freshKey()
            }
            testKeys.add(Pair(KeyOwningIdentity.fromUUID(it), key))
        }
    }

    private fun performTestRun() {
        for ((keyOwningIdentity, key) in testKeys) {
            assertEquals(keyOwningIdentity, testCache[key])
        }
    }

    @Test
    fun `cache returns right key for each UUID`() {
        performTestRun()
    }

    @Test
    fun `querying for key twice does not go to database the second time`() {
        performTestRun()

        withoutDatabaseAccess {
            performTestRun()
        }
    }

    @Test
    fun `entries can be fetched if cache invalidated`() {
        testCache = PublicKeyToOwningIdentityCache(database, 5)
        // Fill the cache
        performTestRun()

        // Run again, as each entry will have been invalidated before being queried
        performTestRun()
    }

    @Test
    fun `cache access is thread safe`() {
        val executor = Executors.newFixedThreadPool(2)
        val f1 = executor.submit { performTestRun() }
        val f2 = executor.submit { performTestRun() }
        f2.getOrThrow()
        f1.getOrThrow()
    }
}