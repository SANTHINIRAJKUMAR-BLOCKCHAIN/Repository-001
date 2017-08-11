package net.corda.nodeapi.internal.serialization.amqp

import org.apache.qpid.proton.codec.Data

class TestSerializationOutput(
        private val verbose: Boolean,
        serializerFactory: SerializerFactory = SerializerFactoryFactory.get()) : SerializationOutput(serializerFactory) {

    override fun writeSchema(schema: Schema, data: Data) {
        if (verbose) println(schema)
        super.writeSchema(schema, data)
    }
}
