package net.corda.node

import co.paralleluniverse.fibers.Suspendable
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import net.corda.core.contracts.AlwaysAcceptAttachmentConstraint
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.startFlow
import net.corda.core.serialization.CustomSerializationScheme
import net.corda.core.serialization.SerializationSchemeContext
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ByteSequence
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.internal.enclosedCordapp
import org.junit.Test
import org.objenesis.instantiator.ObjectInstantiator
import org.objenesis.strategy.InstantiatorStrategy
import org.objenesis.strategy.StdInstantiatorStrategy
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import kotlin.test.assertTrue

class CustomSerializationSchemeDriverTest {

    @Test(timeout = 300_000)
    fun `flow suspend with custom kryo serializer`() {
        driver(DriverParameters(notarySpecs = emptyList(), startNodesInProcess = true, cordappsForAllNodes = listOf(enclosedCordapp()))) {
            val (alice, bob) = listOf(
                    startNode(NodeParameters(providedName = ALICE_NAME)),
                    startNode(NodeParameters(providedName = BOB_NAME))
            ).transpose().getOrThrow()

            val flow = alice.rpc.startFlow(::SendFlow, bob.nodeInfo.legalIdentities.single())
            assertTrue { flow.returnValue.getOrThrow() }
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class SendFlow(val counterparty: Party) : FlowLogic<Boolean>() {
        @Suspendable
        override fun call(): Boolean {
            val outputState = TransactionState(
                    data = DummyContract.DummyState(),
                    contract = DummyContract::class.java.name,
                    notary = counterparty,
                    constraint = AlwaysAcceptAttachmentConstraint
            )
            val builder = TransactionBuilder()
                    .addOutputState(outputState)
                    .addCommand(DummyCommandData, counterparty.owningKey)


            val wtx = createWireTransaction(builder)
            val session = initiateFlow(counterparty)
            session.send(wtx)
            return session.receive<Boolean>().unwrap {it}
        }

        fun createWireTransaction(builder: TransactionBuilder) : WireTransaction {
            return builder.toWireTransaction(serviceHub, KryoScheme.SCHEME_ID)
        }

    }

    @InitiatedBy(SendFlow::class)
    class ReceiveFlow(private val session: FlowSession): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val message = session.receive<WireTransaction>().unwrap {it}
            message.toLedgerTransaction(serviceHub)
            session.send(true)
        }
    }

    class DummyContract: Contract {
        @BelongsToContract(DummyContract::class)
        class DummyState(override val participants: List<AbstractParty> = listOf()) : ContractState
        override fun verify(tx: LedgerTransaction) {
            return
        }
    }

    object DummyCommandData : TypeOnlyCommandData()

    class KryoScheme : CustomSerializationScheme {

        companion object {
            const val SCHEME_ID = 7
        }

        override fun getSchemeId(): Int {
            return SCHEME_ID
        }

        override fun <T : Any> deserialize(bytes: ByteSequence, clazz: Class<T>, context: SerializationSchemeContext): T {
            val kryo = Kryo()
            kryo.instantiatorStrategy = CustomInstantiatorStrategy()
            kryo.classLoader = context.deserializationClassLoader

            val obj = Input(bytes.open()).use {
                kryo.readClassAndObject(it)
            }
            @Suppress("UNCHECKED_CAST")
            return obj as T
        }

        override fun <T : Any> serialize(obj: T, context: SerializationSchemeContext): ByteSequence {
            val kryo = Kryo()
            kryo.instantiatorStrategy = CustomInstantiatorStrategy()
            kryo.classLoader = context.deserializationClassLoader
            val outputStream = ByteArrayOutputStream()
            Output(outputStream).use {
                kryo.writeClassAndObject(it, obj)
            }
            return ByteSequence.of(outputStream.toByteArray())
        }

        //Stolen from DefaultKryoCustomizer.kt
        private class CustomInstantiatorStrategy : InstantiatorStrategy {
            private val fallbackStrategy = StdInstantiatorStrategy()

            // Use this to allow construction of objects using a JVM backdoor that skips invoking the constructors, if there
            // is no no-arg constructor available.
            private val defaultStrategy = Kryo.DefaultInstantiatorStrategy(fallbackStrategy)

            override fun <T> newInstantiatorOf(type: Class<T>): ObjectInstantiator<T> {
                // However this doesn't work for non-public classes in the java. namespace
                val strat = if (type.name.startsWith("java.") && !Modifier.isPublic(type.modifiers)) fallbackStrategy else defaultStrategy
                return strat.newInstantiatorOf(type)
            }
        }
    }
}