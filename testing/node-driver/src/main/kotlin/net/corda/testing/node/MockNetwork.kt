package net.corda.testing.node

import com.google.common.jimfs.Jimfs
import net.corda.core.concurrent.CordaFuture
import net.corda.core.crypto.random63BitValue
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.node.VersionInfo
import net.corda.node.internal.InitiatedFlowFactory
import net.corda.node.internal.StartedNode
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.messaging.MessagingService
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.node.InMemoryMessagingNetwork.TestMessagingService
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.setMessagingServiceSpy
import rx.Observable
import java.math.BigInteger

/**
 * Extend this class in order to intercept and modify messages passing through the [MessagingService] when using the [InMemoryMessagingNetwork].
 */
open class MessagingServiceSpy(val messagingService: MessagingService) : MessagingService by messagingService

/**
 * @param entropyRoot the initial entropy value to use when generating keys. Defaults to an (insecure) random value,
 * but can be overridden to cause nodes to have stable or colliding identity/service keys.
 * @param configOverrides add/override behaviour of the [NodeConfiguration] mock object.
 */
@Suppress("unused")
data class MockNodeParameters(
        val forcedID: Int? = null,
        val legalName: CordaX500Name? = null,
        val entropyRoot: BigInteger = BigInteger.valueOf(random63BitValue()),
        val configOverrides: (NodeConfiguration) -> Any? = {},
        val version: VersionInfo = MockServices.MOCK_VERSION_INFO) {
    fun setForcedID(forcedID: Int?) = copy(forcedID = forcedID)
    fun setLegalName(legalName: CordaX500Name?) = copy(legalName = legalName)
    fun setEntropyRoot(entropyRoot: BigInteger) = copy(entropyRoot = entropyRoot)
    fun setConfigOverrides(configOverrides: (NodeConfiguration) -> Any?) = copy(configOverrides = configOverrides)
}

/** Helper builder for configuring a [InternalMockNetwork] from Java. */
@Suppress("unused")
data class MockNetworkParameters(
        val networkSendManuallyPumped: Boolean = false,
        val threadPerNode: Boolean = false,
        val servicePeerAllocationStrategy: InMemoryMessagingNetwork.ServicePeerAllocationStrategy = InMemoryMessagingNetwork.ServicePeerAllocationStrategy.Random(),
        val initialiseSerialization: Boolean = true,
        val notarySpecs: List<MockNetworkNotarySpec> = listOf(MockNetworkNotarySpec(DUMMY_NOTARY_NAME))) {
    fun setNetworkSendManuallyPumped(networkSendManuallyPumped: Boolean) = copy(networkSendManuallyPumped = networkSendManuallyPumped)
    fun setThreadPerNode(threadPerNode: Boolean) = copy(threadPerNode = threadPerNode)
    fun setServicePeerAllocationStrategy(servicePeerAllocationStrategy: InMemoryMessagingNetwork.ServicePeerAllocationStrategy) = copy(servicePeerAllocationStrategy = servicePeerAllocationStrategy)
    fun setInitialiseSerialization(initialiseSerialization: Boolean) = copy(initialiseSerialization = initialiseSerialization)
    fun setNotarySpecs(notarySpecs: List<MockNetworkNotarySpec>) = copy(notarySpecs = notarySpecs)
}

/** Represents a node configuration for injection via [MockNetworkParameters] **/
data class MockNetworkNotarySpec(val name: CordaX500Name, val validating: Boolean = true) {
    constructor(name: CordaX500Name) : this(name, validating = true)
}

/** A class that represents an unstarted mock node for testing. Do not instantiate directly, create via a [MockNetwork] **/
class UnstartedMockNode {
    private lateinit var node: InternalMockNetwork.MockNode
    val id get() = node.id
    val configuration get() = node.configuration
    /** Start the node **/
    fun start() = StartedMockNode().initialise(node.start())

    /**
     * Intialise via internal function rather than internal constructors, as internal constructors in Kotlin
     * are visible via the Api
     */
    internal fun initialise(node: InternalMockNetwork.MockNode): UnstartedMockNode {
        this.node = node
        return this
    }
}

/** A class that represents a started mock node for testing. Do not instantiate directly, create via a [MockNetwork] **/
class StartedMockNode {
    private lateinit var node: StartedNode<InternalMockNetwork.MockNode>
    val services get() = node.services
    val database get() = node.database
    val id get() = node.internals.id
    val configuration get() = node.internals.configuration
    val allStateMachines get() = node.smm.allStateMachines
    val checkpointStorage get() = node.checkpointStorage
    val smm get() = node.smm
    val info get() = node.services.myInfo
    val network get() = node.network

    /**
     * Intialise via internal function rather than internal constructors, as internal constructors in Kotlin
     * are visible via the Api
     */
    internal fun initialise(node: StartedNode<InternalMockNetwork.MockNode>): StartedMockNode {
        this.node = node
        return this
    }

    /** Register a flow that is registered by another flow **/
    fun <F : FlowLogic<*>> registerInitiatedFlow(initiatedFlowClass: Class<F>) = node.registerInitiatedFlow(initiatedFlowClass)

    /**
     * Attach a [MessagingServiceSpy] to the [InternalMockNetwork.MockNode] allowing
     * interception and modification of messages.
     */
    fun setMessagingServiceSpy(messagingServiceSpy: MessagingServiceSpy) = node.setMessagingServiceSpy(messagingServiceSpy)

    /** Leave the node database connection open when the node is stopped **/
    fun disableDBCloseOnStop() = node.internals.disableDBCloseOnStop()

    /** Close the node database connection**/
    fun manuallyCloseDB() = node.internals.manuallyCloseDB()

    /** Stop the node **/
    fun stop() = node.internals.stop()

    /** Receive a message from the queue. */
    fun pumpReceive(block: Boolean = false): InMemoryMessagingNetwork.MessageTransfer? {
        return (services.networkService as InMemoryMessagingNetwork.TestMessagingService).pumpReceive(block)
    }

    /** Set the acceptable number of fibers than can be live when the node stops. Default is 0. **/
    fun setAcceptableLiveFiberCountOnStop(number: Int) {
        node.internals.acceptableLiveFiberCountOnStop = number
    }

    /** Returns the currently live flows of type [flowClass], and their corresponding result future. */
    fun <F : FlowLogic<*>> findStateMachines(flowClass: Class<F>): List<Pair<F, CordaFuture<*>>> = node.smm.findStateMachines(flowClass)

    /**
     * Register an flow factory for initiating a new flow of type [initiatedFlowClass]
     * on receiving of a flow of type[initiatingFlowClass].
     */
    fun <F : FlowLogic<*>> internalRegisterFlowFactory(initiatingFlowClass: Class<out FlowLogic<*>>,
                                                       flowFactory: InitiatedFlowFactory<F>,
                                                       initiatedFlowClass: Class<F>,
                                                       track: Boolean): Observable<F> = node.internalRegisterFlowFactory(initiatingFlowClass, flowFactory, initiatedFlowClass, track)
}

/**
 * A mock node brings up a suite of in-memory services in a fast manner suitable for unit testing.
 * Components that do IO are either swapped out for mocks, or pointed to a [Jimfs] in memory filesystem or an in
 * memory H2 database instance.
 *
 * Mock network nodes require manual pumping by default: they will not run asynchronous. This means that
 * for message exchanges to take place (and associated handlers to run), you must call the [runNetwork]
 * method.
 *
 * You can get a printout of every message sent by using code like:
 *
 *    LogHelper.setLevel("+messages")
 *
 * By default a single notary node is automatically started, which forms part of the network parameters for all the nodes.
 * This node is available by calling [defaultNotaryNode].
 */
class MockNetwork(
        val cordappPackages: List<String>,
        val defaultParameters: MockNetworkParameters = MockNetworkParameters(),
        val networkSendManuallyPumped: Boolean = defaultParameters.networkSendManuallyPumped,
        val threadPerNode: Boolean = defaultParameters.threadPerNode,
        val servicePeerAllocationStrategy: InMemoryMessagingNetwork.ServicePeerAllocationStrategy = defaultParameters.servicePeerAllocationStrategy,
        val initialiseSerialization: Boolean = defaultParameters.initialiseSerialization,
        val notarySpecs: List<MockNetworkNotarySpec> = defaultParameters.notarySpecs) {
    @JvmOverloads
    constructor(cordappPackages: List<String>, parameters: MockNetworkParameters = MockNetworkParameters()) : this(cordappPackages, defaultParameters = parameters)

    private val internalMockNetwork: InternalMockNetwork = InternalMockNetwork(cordappPackages, defaultParameters, networkSendManuallyPumped, threadPerNode, servicePeerAllocationStrategy, initialiseSerialization, notarySpecs)
    val defaultNotaryNode get() = StartedMockNode().initialise(internalMockNetwork.defaultNotaryNode)
    val defaultNotaryIdentity get() = internalMockNetwork.defaultNotaryIdentity
    val messagingNetwork get() = internalMockNetwork.messagingNetwork
    val notaryNodes get() = internalMockNetwork.notaryNodes.map { StartedMockNode().initialise(it) }
    val nextNodeId get() = internalMockNetwork.nextNodeId

    /** Create a started node with the given identity. **/
    fun createPartyNode(legalName: CordaX500Name? = null) = StartedMockNode().initialise(internalMockNetwork.createPartyNode(legalName))

    /** Create a started node with the given parameters. **/
    fun createNode(parameters: MockNodeParameters = MockNodeParameters()) = StartedMockNode().initialise(internalMockNetwork.createNode(parameters))

    /** Create an unstarted node with the given parameters. **/
    fun createUnstartedNode(parameters: MockNodeParameters = MockNodeParameters()) = UnstartedMockNode().initialise(internalMockNetwork.createUnstartedNode(parameters))

    /** Start all nodes that aren't already started. **/
    fun startNodes() = internalMockNetwork.startNodes()

    /** Stop all nodes. **/
    fun stopNodes() = internalMockNetwork.stopNodes()

    /** Block until all scheduled activity, active flows and network activity has ceased. **/
    fun waitQuiescent() = internalMockNetwork.waitQuiescent()

    /**
     * Asks every node in order to process any queued up inbound messages. This may in turn result in nodes
     * sending more messages to each other, thus, a typical usage is to call runNetwork with the [rounds]
     * parameter set to -1 (the default) which simply runs as many rounds as necessary to result in network
     * stability (no nodes sent any messages in the last round).
     */
    @JvmOverloads
    fun runNetwork(rounds: Int = -1) = internalMockNetwork.runNetwork(rounds)

    /** Get the base directory for the given node id. **/
    fun baseDirectory(nodeId: Int) = internalMockNetwork.baseDirectory(nodeId)
}