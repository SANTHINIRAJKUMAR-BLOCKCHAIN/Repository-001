package net.corda.node.internal

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.readObject
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.deserialize
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.VersionInfo
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.network.PersistentNetworkMapCache
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.network.NodeInfoFilesCopier.Companion.NODE_INFO_FILE_NAME_PREFIX
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.internal.rigorousMock
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NodeTest {
    private abstract class AbstractNodeConfiguration : NodeConfiguration

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    private fun nodeInfoFile() = temporaryFolder.root.listFiles().singleOrNull { it.name.startsWith(NODE_INFO_FILE_NAME_PREFIX) }
    private fun AbstractNode.generateNodeInfo(): NodeInfo {
        assertNull(nodeInfoFile())
        generateAndSaveNodeInfo()
        val path = nodeInfoFile()!!.toPath()
        val nodeInfo = path.readObject<SignedNodeInfo>().raw.deserialize()
        Files.delete(path)
        return nodeInfo
    }

    @Test
    fun `generateAndSaveNodeInfo works`() {
        val nodeAddress = NetworkHostAndPort("0.1.2.3", 456)
        val nodeName = CordaX500Name("Manx Blockchain Corp", "Douglas", "IM")
        val platformVersion = 789
        val dataSourceProperties = makeTestDataSourceProperties()
        val databaseConfig = DatabaseConfig()
        val configuration = rigorousMock<AbstractNodeConfiguration>().also {
            doReturn(nodeAddress).whenever(it).p2pAddress
            doReturn(nodeName).whenever(it).myLegalName
            doReturn(null).whenever(it).notary // Don't add notary identity.
            doReturn(dataSourceProperties).whenever(it).dataSourceProperties
            doReturn(databaseConfig).whenever(it).database
            doReturn(temporaryFolder.root.toPath()).whenever(it).baseDirectory
            doReturn(true).whenever(it).devMode // Needed for identity cert.
            doReturn("tsp").whenever(it).trustStorePassword
            doReturn("ksp").whenever(it).keyStorePassword
        }
        configureDatabase(dataSourceProperties, databaseConfig, rigorousMock()).use { database ->
            val node = Node(configuration, rigorousMock<VersionInfo>().also {
                doReturn(platformVersion).whenever(it).platformVersion
            }, initialiseSerialization = false)
            val nodeInfo = node.generateNodeInfo()
            assertEquals(listOf(nodeAddress), nodeInfo.addresses)
            assertEquals(listOf(nodeName), nodeInfo.legalIdentitiesAndCerts.map { it.name })
            assertEquals(platformVersion, nodeInfo.platformVersion)
            node.generateNodeInfo().let {
                assertNotEquals(nodeInfo, it) // Different serial.
                assertEquals(nodeInfo, it.copy(serial = nodeInfo.serial))
            }
            PersistentNetworkMapCache(database, emptyList()).addNode(nodeInfo)
            assertEquals(nodeInfo, node.generateNodeInfo())
        }
    }
}
