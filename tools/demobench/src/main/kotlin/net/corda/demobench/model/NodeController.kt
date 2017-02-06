package net.corda.demobench.model

import com.typesafe.config.ConfigRenderOptions
import java.lang.management.ManagementFactory
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import net.corda.demobench.pty.R3Pty
import tornadofx.Controller

class NodeController : Controller() {
    companion object Data {
        private const val FIRST_PORT = 10000
    }

    private val baseDir = Paths.get("work", localDir).toAbsolutePath()
    private val pluginDir = Paths.get("plugins").toAbsolutePath()
    private val jvm by inject<JVMConfig>()

    private val bankOfCorda = pluginDir.resolve("bank-of-corda.jar").toFile()

    private val cordaPath = Paths.get("corda", "corda.jar").toAbsolutePath()
    private val command = jvm.commandFor(cordaPath)

    private val renderOptions = ConfigRenderOptions.defaults().setOriginComments(false)

    private val nodes = ConcurrentHashMap<String, NodeConfig>()
    private val port = AtomicInteger(FIRST_PORT)

    private var networkMapConfig: NetworkMapConfig? = null

    init {
        log.info("Base directory: " + baseDir)
        log.info("Corda JAR: " + cordaPath)
    }

    fun validate(nodeData: NodeData): NodeConfig? {
        val config = NodeConfig(
            baseDir,
            nodeData.legalName.value.trim(),
            nodeData.artemisPort.value,
            nodeData.nearestCity.value.trim(),
            nodeData.webPort.value,
            nodeData.h2Port.value,
            nodeData.extraServices.value
        )

        if (nodes.putIfAbsent(config.key, config) != null) {
            log.warning("Node with key '" + config.key + "' already exists.")
            return null
        }

        // The first node becomes our network map
        chooseNetworkMap(config)

        return config
    }

    val nextPort: Int get() = port.andIncrement

    fun keyExists(key: String) = nodes.keys.contains(key)

    fun nameExists(name: String) = keyExists(toKey(name))

    fun chooseNetworkMap(config: NodeConfig) {
        if (networkMapConfig != null) {
            config.networkMap = networkMapConfig
        } else {
            networkMapConfig = config
            log.info("Network map provided by: " + config.legalName)
        }
    }

    fun runCorda(pty: R3Pty, config: NodeConfig): Boolean {
        val nodeDir = config.nodeDir.toFile()

        if (nodeDir.mkdirs()) {
            try {
                // Write this node's configuration file into its working directory.
                val confFile = nodeDir.resolve("node.conf")
                val fileData = config.toFileConfig
                confFile.writeText(fileData.root().render(renderOptions))

                // Nodes cannot issue cash unless they contain the "Bank of Corda" plugin.
                if (config.isCashIssuer && bankOfCorda.isFile) {
                    log.info("Installing 'Bank of Corda' plugin")
                    bankOfCorda.copyTo(nodeDir.resolve("plugins").resolve(bankOfCorda.name))
                }

                // Execute the Corda node
                pty.run(command, System.getenv(), nodeDir.toString())
                log.info("Launched node: " + config.legalName)
                return true
            } catch (e: Exception) {
                log.severe("Failed to launch Corda:" + e)
                return false
            }
        } else {
            return false
        }
    }

    private val localDir: String
        get() = SimpleDateFormat("yyyyMMddHHmmss")
                    .format(Date(ManagementFactory.getRuntimeMXBean().startTime))

}
