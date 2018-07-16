package net.corda.node.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.TestCorDapp
import net.corda.testing.driver.driver
import org.junit.Test
import kotlin.test.assertEquals

class AsymmetricCorDappsTests {

    @StartableByRPC
    @InitiatingFlow
    class Ping(private val pongParty: Party, val times: Int) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val pongSession = initiateFlow(pongParty)
            pongSession.sendAndReceive<Unit>(times)
            for (i in 1..times) {
                val j = pongSession.sendAndReceive<Int>(i).unwrap { it }
                assertEquals(i, j)
            }
        }
    }

    @InitiatedBy(Ping::class)
    class Pong(private val pingSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val times = pingSession.sendAndReceive<Int>(Unit).unwrap { it }
            for (i in 1..times) {
                val j = pingSession.sendAndReceive<Int>(i).unwrap { it }
                assertEquals(i, j)
            }
        }
    }

    @Test
    fun sharedCorDappsWithSpecificClasses() {

        driver(DriverParameters(startNodesInProcess = false, cordappsForAllNodes = setOf(TestCorDapp.builder("Szymon CorDapp", "1.0", classes = setOf(Ping::class.java, Pong::class.java))))) {

            val (nodeA, nodeB) = listOf(startNode(), startNode()).transpose().getOrThrow()
            nodeA.rpc.startFlow(::Ping, nodeB.nodeInfo.singleIdentity(), 1).returnValue.getOrThrow()
        }
    }
}