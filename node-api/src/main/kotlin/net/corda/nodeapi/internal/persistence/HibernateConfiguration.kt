package net.corda.nodeapi.internal.persistence

import com.github.benmanes.caffeine.cache.Caffeine
import net.corda.core.internal.NamedCacheFactory
import net.corda.core.internal.castIfPossible
import net.corda.core.schemas.MappedSchema
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.toHexString
import org.hibernate.SessionFactory
import org.hibernate.boot.Metadata
import org.hibernate.boot.MetadataBuilder
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService
import org.hibernate.cfg.Configuration
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider
import org.hibernate.service.UnknownUnwrapTypeException
import org.hibernate.type.AbstractSingleColumnStandardBasicType
import org.hibernate.type.MaterializedBlobType
import org.hibernate.type.descriptor.java.PrimitiveByteArrayTypeDescriptor
import org.hibernate.type.descriptor.sql.BlobTypeDescriptor
import org.hibernate.type.descriptor.sql.VarbinaryTypeDescriptor
import java.lang.management.ManagementFactory
import java.sql.Connection
import javax.management.ObjectName
import javax.persistence.AttributeConverter

class HibernateConfiguration(
        schemas: Set<MappedSchema>,
        private val databaseConfig: DatabaseConfig,
        private val attributeConverters: Collection<AttributeConverter<*, *>>,
        private val jdbcUrl: String,
        cacheFactory: NamedCacheFactory,
        val customClassLoader: ClassLoader? = null
) {
    companion object {
        private val logger = contextLogger()

//        // register custom converters
//        fun buildHibernateMetadata(metadataBuilder: MetadataBuilder, jdbcUrl:String, attributeConverters: Collection<AttributeConverter<*, *>>): Metadata {
//            metadataBuilder.run {
//                attributeConverters.forEach { applyAttributeConverter(it) }
//                // Register a tweaked version of `org.hibernate.type.MaterializedBlobType` that truncates logged messages.
//                // to avoid OOM when large blobs might get logged.
//                applyBasicType(CordaMaterializedBlobType, CordaMaterializedBlobType.name)
//                applyBasicType(CordaWrapperBinaryType, CordaWrapperBinaryType.name)
//
//                // Create a custom type that will map a blob to byteA in postgres and as a normal blob for all other dbms.
//                // This is required for the Checkpoints as a workaround for the issue that postgres has on azure.
//                if (jdbcUrl.contains(":postgresql:", ignoreCase = true)) {
//                    applyBasicType(MapBlobToPostgresByteA, MapBlobToPostgresByteA.name)
//                } else {
//                    applyBasicType(MapBlobToNormalBlob, MapBlobToNormalBlob.name)
//                }
//
//                return build()
//            }
//        }
    }

    private val sessionFactories = cacheFactory.buildNamed<Set<MappedSchema>, SessionFactory>(Caffeine.newBuilder(), "HibernateConfiguration_sessionFactories")

    val sessionFactoryForRegisteredSchemas = schemas.let {
        logger.info("Init HibernateConfiguration for schemas: $it")
        sessionFactoryForSchemas(it)
    }

    /** @param key must be immutable, not just read-only. */
    fun sessionFactoryForSchemas(key: Set<MappedSchema>): SessionFactory = sessionFactories.get(key, ::makeSessionFactoryForSchemas)!!

    private fun makeSessionFactoryForSchemas(schemas: Set<MappedSchema>): SessionFactory {
        val sessionFactoryFactory = H2SessionFactoryFactory()
        val sessionFactory = sessionFactoryFactory.makeSessionFactoryForSchemas(databaseConfig, schemas, customClassLoader, attributeConverters)

        // export Hibernate JMX statistics
        if (databaseConfig.exportHibernateJMXStatistics)
            initStatistics(sessionFactory)

        return sessionFactory
    }

    // NOTE: workaround suggested to overcome deprecation of StatisticsService (since Hibernate v4.0)
    // https://stackoverflow.com/questions/23606092/hibernate-upgrade-statisticsservice
    fun initStatistics(sessionFactory: SessionFactory) {
        val statsName = ObjectName("org.hibernate:type=statistics")
        val mbeanServer = ManagementFactory.getPlatformMBeanServer()

        val statisticsMBean = DelegatingStatisticsService(sessionFactory.statistics)
        statisticsMBean.isStatisticsEnabled = true

        try {
            mbeanServer.registerMBean(statisticsMBean, statsName)
        } catch (e: Exception) {
            logger.warn(e.message)
        }
    }

    // Supply Hibernate with connections from our underlying Exposed database integration.  Only used
    // during schema creation / update.
    class NodeDatabaseConnectionProvider : ConnectionProvider {
        override fun closeConnection(conn: Connection) {
            conn.autoCommit = false
            contextTransaction.run {
                commit()
                close()
            }
        }

        override fun supportsAggressiveRelease(): Boolean = true

        override fun getConnection(): Connection {
            return contextDatabase.newTransaction().connection
        }

        override fun <T : Any?> unwrap(unwrapType: Class<T>): T {
            return unwrapType.castIfPossible(this) ?: throw UnknownUnwrapTypeException(unwrapType)
        }

        override fun isUnwrappableAs(unwrapType: Class<*>?): Boolean = unwrapType == NodeDatabaseConnectionProvider::class.java
    }

    // Maps to a byte array on postgres.
    object MapBlobToPostgresByteA : AbstractSingleColumnStandardBasicType<ByteArray>(VarbinaryTypeDescriptor.INSTANCE, PrimitiveByteArrayTypeDescriptor.INSTANCE) {
        override fun getRegistrationKeys(): Array<String> {
            return arrayOf(name, "ByteArray", ByteArray::class.java.name)
        }

        override fun getName(): String {
            return "corda-blob"
        }
    }

}
