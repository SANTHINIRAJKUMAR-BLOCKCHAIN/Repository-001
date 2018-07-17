package net.corda.node.flows

import net.corda.client.rpc.CordaRPCClient
import net.corda.core.internal.div
import net.corda.core.internal.packageName
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.NodeStartup
import net.corda.node.internal.cordapp.CordappLoader
import net.corda.node.services.Permissions
import net.corda.testMessage.Message
import net.corda.testMessage.MessageState
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.driver.internal.RandomFree
import net.corda.testing.node.User
import net.test.cordapp.v1.SendMessageFlowY
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FlowCheckpointVersionNodeStartupCheck {
    companion object {
        val message = Message("Hello world!")
    }

    @Before
    fun setUp() {
        //for in-process nodes the cache of CordappLoader should be invalidated
        CordappLoader.invalidateCache()
    }

    @Test
    fun `restart nodes with sunspended flow`() {

        val cordappsVersionAtStartup = mapOf("net.test.cordapp.v1" to "fancy")
        val cordappsVersionAtRestart = mapOf("net.test.cordapp.v1" to "fancy")

        val user = User("mark", "dadada", setOf(Permissions.startFlow<SendMessageFlowY>(), Permissions.invokeRpc("vaultQuery"), Permissions.invokeRpc("vaultTrack")))
        return driver(DriverParameters(isDebug = true, startNodesInProcess = true, inMemoryDB = false, portAllocation = RandomFree, extraCordappPackagesToScan = listOf(MessageState::class.packageName))) {
            {
                val alice = startNode(rpcUsers = listOf(user), providedName = ALICE_NAME, packageToGeneratedJarName = cordappsVersionAtStartup).getOrThrow()
                val bob = startNode(rpcUsers = listOf(user), providedName = BOB_NAME, packageToGeneratedJarName = cordappsVersionAtStartup).getOrThrow()
                alice.stop()
                CordaRPCClient(bob.rpcAddress).start(user.username, user.password).use {
                    val flowTracker = it.proxy.startTrackedFlow(::SendMessageFlowY, message, defaultNotaryIdentity, alice.nodeInfo.singleIdentity()).progress
                    //wait until Bob progresses as far as possible because alice node is off
                    flowTracker.takeFirst { it == SendMessageFlowY.Companion.FINALISING_TRANSACTION.label }.toBlocking().single()
                }
                bob.stop()
            }()
            //for in-process nodes the cache of CordappLoader should be invalidated
            CordappLoader.invalidateCache()
            val result = {
                //Bob will resume the flow
                val alice = startNode(rpcUsers = listOf(user), providedName = ALICE_NAME, customOverrides = mapOf("devMode" to false), packageToGeneratedJarName = cordappsVersionAtRestart).getOrThrow()
                startNode(providedName = BOB_NAME, rpcUsers = listOf(user), customOverrides = mapOf("devMode" to false), packageToGeneratedJarName = cordappsVersionAtRestart).getOrThrow()
                CordaRPCClient(alice.rpcAddress).start(user.username, user.password).use {
                    val page = it.proxy.vaultTrack(MessageState::class.java)
                    if (page.snapshot.states.isNotEmpty()) {
                        page.snapshot.states.first()
                    } else {
                        val r = page.updates.timeout(10, TimeUnit.SECONDS).take(1).toBlocking().single()
                        if (r.consumed.isNotEmpty()) r.consumed.first() else r.produced.first()
                    }
                }
            }()
            assertNotNull(result)
            assertEquals(message, result.state.data.message)
        }
    }

    @Test
    fun `restart nodes with incompatible version of sunspended flow`() {

        val cordappsVersionAtStartup = mapOf("net.test.cordapp.v1" to "fancy")
        val cordappsVersionAtRestart = mapOf("net.test.cordapp" to "fancy")  // including now addtional Dummy class to change hash of JAr file, despite reusing the same file name

        val user = User("mark", "dadada", setOf(Permissions.startFlow<SendMessageFlowY>(), Permissions.invokeRpc("vaultQuery"), Permissions.invokeRpc("vaultTrack")))
        return driver(DriverParameters(isDebug = true, startNodesInProcess = true, inMemoryDB = false,
                portAllocation = RandomFree, extraCordappPackagesToScan = listOf(MessageState::class.packageName))) {
            {
                val alice = startNode(rpcUsers = listOf(user), providedName = ALICE_NAME, packageToGeneratedJarName = cordappsVersionAtStartup).getOrThrow()
                val bob = startNode(rpcUsers = listOf(user), providedName = BOB_NAME, packageToGeneratedJarName = cordappsVersionAtStartup).getOrThrow()
                alice.stop()
                CordaRPCClient(bob.rpcAddress).start(user.username, user.password).use {
                    val flowTracker = it.proxy.startTrackedFlow(::SendMessageFlowY, message, defaultNotaryIdentity, alice.nodeInfo.singleIdentity()).progress
                    //wait until Bob progresses as far as possible because alice node is off
                    flowTracker.takeFirst { it == SendMessageFlowY.Companion.FINALISING_TRANSACTION.label }.toBlocking().single()
                }
                val logFolder = bob.baseDirectory / NodeStartup.LOGS_DIRECTORY_NAME
                bob.stop()
                logFolder
            }()
            //for in-process nodes the cache of CordappLoader should be invalidated
            CordappLoader.invalidateCache()

            startNode(rpcUsers = listOf(user), providedName = ALICE_NAME, customOverrides = mapOf("devMode" to false), packageToGeneratedJarName = cordappsVersionAtRestart).getOrThrow()
            assertFailsWith(net.corda.node.internal.CheckpointIncompatibleException.FlowVersionIncompatibleException::class) {
                startNode(providedName = BOB_NAME, rpcUsers = listOf(user), customOverrides = mapOf("devMode" to false), packageToGeneratedJarName = cordappsVersionAtRestart).getOrThrow()
            }
        }
    }


    @Test
    fun `restart nodes with incompatible version of sunspended flow - different jar name`() {

        val cordappsVersionAtStartup = mapOf("net.test.cordapp.v1" to null)  // no JAR file name, CordappLoader will generate a name with random UUID so between restarts, jAR file name will change
        val cordappsVersionAtRestart = mapOf("net.test.cordapp.v1" to null)

        val user = User("mark", "dadada", setOf(Permissions.startFlow<SendMessageFlowY>(), Permissions.invokeRpc("vaultQuery"), Permissions.invokeRpc("vaultTrack")))
        return driver(DriverParameters(isDebug = true, startNodesInProcess = true, inMemoryDB = false,
                portAllocation = RandomFree, extraCordappPackagesToScan = listOf(MessageState::class.packageName))) {
            {
                val alice = startNode(rpcUsers = listOf(user), providedName = ALICE_NAME, packageToGeneratedJarName = cordappsVersionAtStartup).getOrThrow()
                val bob = startNode(rpcUsers = listOf(user), providedName = BOB_NAME, packageToGeneratedJarName = cordappsVersionAtStartup).getOrThrow()
                alice.stop()
                CordaRPCClient(bob.rpcAddress).start(user.username, user.password).use {
                    val flowTracker = it.proxy.startTrackedFlow(::SendMessageFlowY, message, defaultNotaryIdentity, alice.nodeInfo.singleIdentity()).progress
                    //wait until Bob progresses as far as possible because alice node is off
                    flowTracker.takeFirst { it == SendMessageFlowY.Companion.FINALISING_TRANSACTION.label }.toBlocking().single()
                }
                val logFolder = bob.baseDirectory / NodeStartup.LOGS_DIRECTORY_NAME
                bob.stop()
                logFolder
            }()
            //for in-process nodes the cache of CordappLoader should be invalidated
            CordappLoader.invalidateCache()

            startNode(rpcUsers = listOf(user), providedName = ALICE_NAME, customOverrides = mapOf("devMode" to false), packageToGeneratedJarName = cordappsVersionAtRestart).getOrThrow()
            assertFailsWith(net.corda.node.internal.CheckpointIncompatibleException.FlowNotInstalledException::class) {
                startNode(providedName = BOB_NAME, rpcUsers = listOf(user), customOverrides = mapOf("devMode" to false), packageToGeneratedJarName = cordappsVersionAtRestart).getOrThrow()
            }
        }
    }
}