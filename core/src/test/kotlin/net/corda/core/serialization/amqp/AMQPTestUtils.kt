package net.corda.core.serialization.amqp.test

import org.apache.qpid.proton.codec.Data
import net.corda.core.serialization.amqp.Schema
import net.corda.core.serialization.amqp.SerializerFactory
import net.corda.core.serialization.amqp.SerializationOutput
import net.corda.core.serialization.amqp.SerializerFactoryFactory

class TestSerializationOutput(
        private val verbose: Boolean,
        serializerFactory: SerializerFactory = SerializerFactoryFactory.get()) : SerializationOutput(serializerFactory) {

    override fun writeSchema(schema: Schema, data: Data) {
        if (verbose) println(schema)
        super.writeSchema(schema, data)
    }
}
