package net.corda.core.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import net.corda.core.serialization.amqp.DeserializationInput
import net.corda.core.serialization.amqp.SerializationOutput
import net.corda.core.serialization.amqp.SerializerFactory

/**
 * This [Kryo] custom [Serializer] switches the object graph of anything annotated with `@CordaSerializable`
 * to using the AMQP serialization wire format, and simply writes that out as bytes to the wire.
 *
 * Currently this writes a variable length integer to the stream indicating how many bytes of AMQP encoded bytes follow
 * and then that many raw bytes.
 */
// TODO: Consider setting the immutable flag on the `Serializer` if we make all AMQP types immutable.
object KryoAMQPSerializer : Serializer<Any>() {
    internal fun registerCustomSerializers(factory: SerializerFactory) {
        factory.apply {
            register(net.corda.core.serialization.amqp.custom.PublicKeySerializer)
            register(net.corda.core.serialization.amqp.custom.ThrowableSerializer(this))
            register(net.corda.core.serialization.amqp.custom.X500NameSerializer)
            register(net.corda.core.serialization.amqp.custom.BigDecimalSerializer)
            register(net.corda.core.serialization.amqp.custom.CurrencySerializer)
            register(net.corda.core.serialization.amqp.custom.InstantSerializer(this))
        }
    }

    // TODO: need to sort out the whitelist...
    private val serializerFactory = SerializerFactory().apply {
        registerCustomSerializers(this)
    }

    override fun write(kryo: Kryo, output: Output, `object`: Any) {
        val amqpOutput = SerializationOutput(serializerFactory)
        val bytes = amqpOutput.serialize(`object`).bytes
        output.writeVarInt(bytes.size, true)
        output.write(bytes)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<Any>): Any {
        val amqpInput = DeserializationInput(serializerFactory)
        @Suppress("UNCHECKED_CAST")
        val size = input.readVarInt(true)
        return amqpInput.deserialize(SerializedBytes<Any>(input.readBytes(size)), type)
    }
}