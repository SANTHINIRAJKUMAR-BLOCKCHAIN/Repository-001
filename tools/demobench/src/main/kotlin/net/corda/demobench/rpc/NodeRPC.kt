package net.corda.demobench.rpc

import com.google.common.net.HostAndPort
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.messaging.CordaRPCOps
import net.corda.demobench.loggerFor
import net.corda.demobench.model.NodeConfig

class NodeRPC(config: NodeConfig, start: () -> Unit, invoke: (CordaRPCOps) -> Unit): AutoCloseable {

    private companion object {
        val log = loggerFor<NodeRPC>()
        val oneSecond = SECONDS.toMillis(1)
    }

    private val rpcClient = CordaRPCClient(HostAndPort.fromParts("localhost", config.rpcPort))
    private val timer = Timer()

    init {
        val setupTask = object : TimerTask() {
            override fun run() {
                try {
                    rpcClient.start(config.users[0].user, config.users[0].password)
                    val ops = rpcClient.proxy()

                    // Cancel the "setup" task now that we've created the RPC client.
                    this.cancel()

                    // Run "start-up" task, now that the RPC client is ready.
                    start()

                    // Schedule a new task that will refresh the display once per second.
                    timer.schedule(object: TimerTask() {
                        override fun run() {
                            invoke(ops)
                        }
                    }, 0, oneSecond)
                } catch (e: Exception) {
                    log.warn("Node '{}' not ready yet (Error: {})", config.legalName, e.message)
                }
            }
        }

        // Wait 5 seconds for the node to start, and then poll once per second.
        timer.schedule(setupTask, 5 * oneSecond, oneSecond)
    }

    override fun close() {
        timer.cancel()
        rpcClient.close()
    }

}
