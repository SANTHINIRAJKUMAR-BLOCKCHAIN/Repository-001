package net.corda.node

import net.corda.core.internal.div
import net.corda.node.internal.NodeStartup
import net.corda.nodeapi.internal.config.UnknownConfigKeysPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.BeforeClass
import org.junit.Test
import org.slf4j.event.Level
import java.nio.file.Path
import java.nio.file.Paths

class NodeCmdLineOptionsTest {
    private val parser = NodeStartup()

    companion object {
        private lateinit var workingDirectory: Path

        @BeforeClass
        @JvmStatic
        fun initDirectories() {
            workingDirectory = Paths.get(".").normalize().toAbsolutePath()
        }
    }

    @Test
    fun `no command line arguments`() {
        assertThat(parser.cmdLineOptions.baseDirectory).isEqualTo(workingDirectory)
        assertThat(parser.cmdLineOptions.configFile).isEqualTo(workingDirectory / "node.conf")
        assertThat(parser.verbose).isEqualTo(false)
        assertThat(parser.loggingLevel).isEqualTo(Level.INFO)
        assertThat(parser.cmdLineOptions.nodeRegistrationOption).isEqualTo(null)
        assertThat(parser.cmdLineOptions.noLocalShell).isEqualTo(false)
        assertThat(parser.cmdLineOptions.sshdServer).isEqualTo(false)
        assertThat(parser.cmdLineOptions.justGenerateNodeInfo).isEqualTo(false)
        assertThat(parser.cmdLineOptions.justGenerateRpcSslCerts).isEqualTo(false)
        assertThat(parser.cmdLineOptions.bootstrapRaftCluster).isEqualTo(false)
        assertThat(parser.cmdLineOptions.unknownConfigKeysPolicy).isEqualTo(UnknownConfigKeysPolicy.FAIL)
        assertThat(parser.cmdLineOptions.devMode).isEqualTo(null)
        assertThat(parser.cmdLineOptions.clearNetworkMapCache).isEqualTo(false)
        assertThat(parser.cmdLineOptions.networkRootTrustStorePath).isEqualTo(workingDirectory / "certificates" / "network-root-truststore.jks")
    }
}
