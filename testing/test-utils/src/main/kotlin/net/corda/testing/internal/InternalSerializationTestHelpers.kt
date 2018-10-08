package net.corda.testing.internal

import com.nhaarman.mockito_kotlin.doNothing
import com.nhaarman.mockito_kotlin.whenever
import net.corda.client.rpc.internal.serialization.amqp.AMQPClientSerializationScheme
import net.corda.core.DoNotImplement
import net.corda.core.serialization.internal.*
import net.corda.node.serialization.amqp.AMQPServerSerializationScheme
import net.corda.node.serialization.kryo.KRYO_CHECKPOINT_CONTEXT
import net.corda.node.serialization.kryo.KryoCheckpointSerializer
import net.corda.serialization.internal.*
import net.corda.testing.core.SerializationEnvironmentRule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

val inVMExecutors = ConcurrentHashMap<SerializationEnvironment, ExecutorService>()

/**
 * For example your test class uses [SerializationEnvironmentRule] but you want to turn it off for one method.
 * Use sparingly, ideally a test class shouldn't mix serializers init mechanisms.
 */
fun <T> withoutTestSerialization(callable: () -> T): T { // TODO: Delete this, see CORDA-858.
    val (property, env) = listOf(_contextSerializationEnv, _inheritableContextSerializationEnv).map { Pair(it, it.get()) }.single { it.second != null }
    property.set(null)
    try {
        return callable()
    } finally {
        property.set(env)
    }
}

internal fun createTestSerializationEnv(label: String): SerializationEnvironment =
    SerializationEnvironment.with(
            nonCheckpoint = NonCheckpointEnvironment(
                    factory = serializationFactory(
                            AMQPClientSerializationScheme(emptyList()),
                            AMQPServerSerializationScheme(emptyList())),
                    contexts = SerializationContexts(
                            p2p = AMQP_P2P_CONTEXT,
                            storage = AMQP_STORAGE_CONTEXT,
                            rpc = RPCSerializationContexts(
                                server = AMQP_RPC_SERVER_CONTEXT,
                                client = AMQP_RPC_CLIENT_CONTEXT
                            ))),
            checkpoint = CheckpointEnvironment(
            context = KRYO_CHECKPOINT_CONTEXT,
            serializer = KryoCheckpointSerializer))

/**
 * Should only be used by Driver and MockNode.
 * @param armed true to install, false to do nothing and return a dummy env.
 */
fun setGlobalSerialization(armed: Boolean): GlobalSerializationEnvironment {
    return if (armed) {
        object : GlobalSerializationEnvironment, SerializationEnvironment by createTestSerializationEnv("<global>") {
            override fun unset() {
                _globalSerializationEnv.set(null)
                inVMExecutors.remove(this)
            }
        }.also {
            _globalSerializationEnv.set(it)
        }
    } else {
        rigorousMock<GlobalSerializationEnvironment>().also {
            doNothing().whenever(it).unset()
        }
    }
}

@DoNotImplement
interface GlobalSerializationEnvironment : SerializationEnvironment {
    /** Unset this environment. */
    fun unset()
}

