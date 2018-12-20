package net.corda.node.persistence

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.packageName
import net.corda.core.messaging.startFlow
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions.Companion.invokeRpc
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.corda.core.utilities.unwrap
import net.corda.testMessage.*
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.cordappForPackages
import net.corda.testing.node.internal.startFlow

class NodeStatePersistenceTests {
    @Test
    fun `persistent state survives node restart`() {
        val user = User("mark", "dadada", setOf(startFlow<SendMessageFlow>(), invokeRpc("vaultQuery")))
        val message = Message("Hello world!")
        val stateAndRef: StateAndRef<MessageState>? = driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                extraCordappPackagesToScan = listOf(MessageState::class.packageName)
        )) {
            val nodeName = {
                val nodeHandle = startNode(rpcUsers = listOf(user)).getOrThrow()
                val nodeName = nodeHandle.nodeInfo.singleIdentity().name
                CordaRPCClient(nodeHandle.rpcAddress).start(user.username, user.password).use {
                    it.proxy.startFlow(::SendMessageFlow, message, defaultNotaryIdentity).returnValue.getOrThrow()
                }
                nodeHandle.stop()
                nodeName
            }()

            val nodeHandle = startNode(providedName = nodeName, rpcUsers = listOf(user)).getOrThrow()
            val result = CordaRPCClient(nodeHandle.rpcAddress).start(user.username, user.password).use {
                val page = it.proxy.vaultQuery(MessageState::class.java)
                page.states.singleOrNull()
            }
            nodeHandle.stop()
            result
        }
        assertNotNull(stateAndRef)
        val retrievedMessage = stateAndRef!!.state.data.message
        assertEquals(message, retrievedMessage)
    }

    @Test
    fun `persistent state survives node restart without reinitialising database schema`() {
        // Temporary disable this test when executed on Windows. It is known to be sporadically failing.
        // More investigation is needed to establish why.
        assumeFalse(System.getProperty("os.name").toLowerCase().startsWith("win"))

        val user = User("mark", "dadada", setOf(startFlow<SendMessageFlow>(), invokeRpc("vaultQuery")))
        val message = Message("Hello world!")
        val stateAndRef: StateAndRef<MessageState>? = driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                extraCordappPackagesToScan = listOf(MessageState::class.packageName)
        )) {
            val nodeName = {
                val nodeHandle = startNode(rpcUsers = listOf(user)).getOrThrow()
                val nodeName = nodeHandle.nodeInfo.singleIdentity().name
                CordaRPCClient(nodeHandle.rpcAddress).start(user.username, user.password).use {
                    it.proxy.startFlow(::SendMessageFlow, message, defaultNotaryIdentity).returnValue.getOrThrow()
                }
                nodeHandle.stop()
                nodeName
            }()

            val nodeHandle = startNode(providedName = nodeName, rpcUsers = listOf(user), customOverrides = mapOf("devMode" to "false")).getOrThrow()
            val result = CordaRPCClient(nodeHandle.rpcAddress).start(user.username, user.password).use {
                val page = it.proxy.vaultQuery(MessageState::class.java)
                page.states.singleOrNull()
            }
            nodeHandle.stop()
            result
        }
        assertNotNull(stateAndRef)
        val retrievedMessage = stateAndRef!!.state.data.message
        assertEquals(message, retrievedMessage)
    }


    @Test
    fun `Broadcasting an old transaction does not cause 2 unconsumed states`() {
        val mockNet = InternalMockNetwork(
                cordappsForAllNodes = listOf(cordappForPackages(MessageState::class.packageName)),
                networkSendManuallyPumped = false,
                threadPerNode = true)

        val node = mockNet.createPartyNode(ALICE_NAME)
        val regulator = mockNet.createPartyNode(BOB_NAME)
        val notary = mockNet.defaultNotaryIdentity
        regulator.registerInitiatedFlow(ReceiveReportedTransaction::class.java)

        fun buildTransactionChain(initialMessage: Message, chainLength: Int) {
            node.services.startFlow(SendMessageFlow(initialMessage, notary)).resultFuture.getOrThrow()
            var result = node.services.vaultService.queryBy(MessageState::class.java).states.filter {
                it.state.data.message.value.startsWith(initialMessage.value)
            }.singleOrNull()

            for (_i in 0.until(chainLength -1 )) {
                node.services.startFlow(SendMessageFlowConsuming(result!!, notary)).resultFuture.getOrThrow()
                result = node.services.vaultService.queryBy(MessageState::class.java).states.filter {
                    it.state.data.message.value.startsWith(initialMessage.value)
                }.singleOrNull()
            }
        }

        fun sendTransactionToObserver(transactionIdx: Int) {
            val transactionList = node.services.validatedTransactions.track().snapshot
            node.services.startFlow(ReportToCounterparty(regulator.info.singleIdentity(), transactionList[transactionIdx])).resultFuture.getOrThrow()
        }

        fun checkObserverTransactions(expectedMessage: Message) {
            val regulatorStates = regulator.services.vaultService.queryBy(MessageState::class.java).states.filter {
                it.state.data.message.value.startsWith(expectedMessage.value[0])
            }

            assertNotNull(regulatorStates, "Could not find any regulator states")
            assertEquals(1, regulatorStates.size, "Incorrect number of unconsumed regulator states")
            val retrievedMessage = regulatorStates.singleOrNull()!!.state.data.message
            assertEquals(expectedMessage, retrievedMessage, "Final unconsumed regulator state is incorrect")
        }

        // Check that sending an old transaction doesn't result in a new unconsumed state
        val message = Message("A")
        buildTransactionChain(message, 4)
        sendTransactionToObserver(3)
        sendTransactionToObserver(1)
        val outputMessage = Message("AAAA")
        checkObserverTransactions(outputMessage)

        mockNet.stopNodes()
    }
}


fun isQuasarAgentSpecified(): Boolean {
    val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments
    return jvmArgs.any { it.startsWith("-javaagent:") && it.contains("quasar") }
}

@StartableByRPC
class SendMessageFlow(private val message: Message, private val notary: Party) : FlowLogic<SignedTransaction>() {
    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on the message.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(GENERATING_TRANSACTION, VERIFYING_TRANSACTION, SIGNING_TRANSACTION, FINALISING_TRANSACTION)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = GENERATING_TRANSACTION

        val messageState = MessageState(message = message, by = ourIdentity)
        val txCommand = Command(MessageContract.Commands.Send(), messageState.participants.map { it.owningKey })
        val txBuilder = TransactionBuilder(notary).withItems(StateAndContract(messageState, MESSAGE_CONTRACT_PROGRAM_ID), txCommand)

        progressTracker.currentStep = VERIFYING_TRANSACTION
        txBuilder.toWireTransaction(serviceHub).toLedgerTransaction(serviceHub).verify()

        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(FinalityFlow(signedTx, emptyList(), FINALISING_TRANSACTION.childProgressTracker()))
    }
}



@StartableByRPC
class SendMessageFlowConsuming(private val stateRef: StateAndRef<MessageState>, private val notary: Party) : FlowLogic<SignedTransaction>() {
    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on the message.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(GENERATING_TRANSACTION, VERIFYING_TRANSACTION, SIGNING_TRANSACTION, FINALISING_TRANSACTION)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = GENERATING_TRANSACTION

        val oldMessageState = stateRef.state.data
        val messageState = MessageState(Message(oldMessageState.message.value + "A"), ourIdentity,  stateRef.state.data.linearId)
        val txCommand = Command(MessageContract.Commands.Send(), messageState.participants.map { it.owningKey })
        val txBuilder = TransactionBuilder(notary).withItems(StateAndContract(messageState, MESSAGE_CONTRACT_PROGRAM_ID), txCommand, stateRef)

        progressTracker.currentStep = VERIFYING_TRANSACTION
        txBuilder.toWireTransaction(serviceHub).toLedgerTransaction(serviceHub).verify()

        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(FinalityFlow(signedTx, emptyList(), FINALISING_TRANSACTION.childProgressTracker()))
    }
}

@InitiatingFlow
@StartableByRPC
class ReportToCounterparty(
        private val regulator: Party,
        private val signedTx: SignedTransaction) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val session = initiateFlow(regulator)

        subFlow(IdentitySyncFlow.Send(session, signedTx.tx))

        subFlow(SendTransactionFlow(session, signedTx))
        val stx = session.receive<SignedTransaction>().unwrap { it }
        return stx
    }
}


@InitiatedBy(ReportToCounterparty::class)
class ReceiveReportedTransaction(private val otherSideSession: FlowSession) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        // TODO: add error handling

        subFlow(IdentitySyncFlow.Receive(otherSideSession))

        val recorded = subFlow(ReceiveTransactionFlow(otherSideSession, true, StatesToRecord.ALL_VISIBLE))

        otherSideSession.send(recorded)
    }
}