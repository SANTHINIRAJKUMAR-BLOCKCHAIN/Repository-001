package net.corda.serialization.internal.amqp

import net.corda.core.serialization.SerializationContext
import net.corda.serialization.internal.model.*
import org.apache.qpid.proton.amqp.Symbol
import org.apache.qpid.proton.codec.Data
import java.io.NotSerializableException
import java.lang.reflect.Type

interface ObjectSerializer : AMQPSerializer<Any> {

    val propertySerializers: Map<String, ComposableTypePropertySerializer>
    val fields: List<Field>

    companion object {
        fun make(typeInformation: LocalTypeInformation, factory: LocalSerializerFactory): ObjectSerializer {
            val typeDescriptor = factory.createDescriptor(typeInformation.observedType)
            val typeNotation = TypeNotationGenerator(factory).getTypeNotation(typeInformation)

            return when (typeInformation) {
                is LocalTypeInformation.Composable ->
                    makeForComposable(typeInformation, typeNotation, typeDescriptor, factory)
                is LocalTypeInformation.AnInterface ->
                    makeForAbstract(typeNotation, typeInformation.interfaces, typeInformation.properties,
                            typeInformation, typeDescriptor, factory)
                is LocalTypeInformation.Abstract ->
                    makeForAbstract(typeNotation, typeInformation.interfaces, typeInformation.properties,
                            typeInformation, typeDescriptor, factory)
                else -> throw NotSerializableException("Cannot build object serializer for $typeInformation")
            }
        }

        private fun makeForAbstract(typeNotation: CompositeType,
                                    interfaces: List<LocalTypeInformation>,
                                    properties: Map<PropertyName, LocalPropertyInformation>,
                                    typeInformation: LocalTypeInformation,
                                    typeDescriptor: Symbol,
                                    factory: LocalSerializerFactory): AbstractObjectSerializer {
            val propertySerializers = makePropertySerializers(properties, factory)
            val writer = ComposableObjectWriter(typeNotation, interfaces, propertySerializers)
            return AbstractObjectSerializer(typeInformation.observedType, typeDescriptor, propertySerializers,
                    typeNotation.fields, writer)
        }

        private fun makeForComposable(typeInformation: LocalTypeInformation.Composable,
                                      typeNotation: CompositeType,
                                      typeDescriptor: Symbol,
                                      factory: LocalSerializerFactory): ComposableObjectSerializer {
            val propertySerializers = makePropertySerializers(typeInformation.properties, factory)
            val reader = ComposableObjectReader(
                    typeInformation.typeIdentifier,
                    propertySerializers,
                    ObjectBuilder.makeProvider(typeInformation))

            val writer = ComposableObjectWriter(
                    typeNotation,
                    typeInformation.interfaces,
                    propertySerializers)

            return ComposableObjectSerializer(
                    typeInformation.observedType,
                    typeDescriptor,
                    propertySerializers,
                    typeNotation.fields,
                    reader,
                    writer)
        }

        private fun makePropertySerializers(properties: Map<PropertyName, LocalPropertyInformation>,
                                            factory: LocalSerializerFactory): Map<String, ComposableTypePropertySerializer> =
                properties.mapValues { (name, property) ->
                    ComposableTypePropertySerializer.make(name, property, factory)
                }
    }
}

class ComposableObjectSerializer(
        override val type: Type,
        override val typeDescriptor: Symbol,
        override val propertySerializers: Map<PropertyName, ComposableTypePropertySerializer>,
        override val fields: List<Field>,
        private val reader: ComposableObjectReader,
        private val writer: ComposableObjectWriter): ObjectSerializer {

    override fun writeClassInfo(output: SerializationOutput) = writer.writeClassInfo(output)

    override fun writeObject(obj: Any, data: Data, type: Type, output: SerializationOutput, context: SerializationContext, debugIndent: Int) =
            writer.writeObject(obj, data, type, output, context, debugIndent)

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput, context: SerializationContext): Any =
            reader.readObject(obj, schemas, input, context)
}

class ComposableObjectWriter(
        private val typeNotation: TypeNotation,
        private val interfaces: List<LocalTypeInformation>,
        private val propertySerializers: Map<PropertyName, ComposableTypePropertySerializer>
) {
    fun writeClassInfo(output: SerializationOutput) {
        if (output.writeTypeNotations(typeNotation)) {
            for (iface in interfaces) {
                output.requireSerializer(iface.observedType)
            }

            propertySerializers.values.forEach { serializer ->
                serializer.writeClassInfo(output)
            }
        }
    }

    fun writeObject(obj: Any, data: Data, type: Type, output: SerializationOutput, context: SerializationContext, debugIndent: Int) {
        data.withDescribed(typeNotation.descriptor) {
            withList {
                propertySerializers.values.forEach { propertySerializer ->
                    propertySerializer.writeProperty(obj, this, output, context, debugIndent + 1)
                }
            }
        }
    }
}

class ComposableObjectReader(
        val typeIdentifier: TypeIdentifier,
        private val propertySerializers: Map<PropertyName, ComposableTypePropertySerializer>,
        private val objectBuilderProvider: () -> ObjectBuilder
) {

    fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput, context: SerializationContext): Any =
            ifThrowsAppend({ typeIdentifier.prettyPrint(false) }) {
                if (obj !is List<*>) throw NotSerializableException("Body of described type is unexpected $obj")
                if (obj.size < propertySerializers.size) {
                    throw NotSerializableException("${obj.size} objects to deserialize, but " +
                            "${propertySerializers.size} properties in described type ${typeIdentifier.prettyPrint(false)}")
                }

                val builder = objectBuilderProvider()
                builder.initialize()
                obj.asSequence().zip(propertySerializers.values.asSequence())
                        // Read _all_ properties from the stream
                        .map { (item, property) -> property to property.readProperty(item, schemas, input, context) }
                        // Throw away any calculated properties
                        .filter { (property, _) -> !property.isCalculated }
                        // Write the rest into the builder
                        .forEachIndexed { slot, (_, propertyValue) -> builder.populate(slot, propertyValue) }
                return builder.build()
            }
}

class AbstractObjectSerializer(
        override val type: Type,
        override val typeDescriptor: Symbol,
        override val propertySerializers: Map<PropertyName, ComposableTypePropertySerializer>,
        override val fields: List<Field>,
        private val writer: ComposableObjectWriter): ObjectSerializer {
    override fun writeClassInfo(output: SerializationOutput) =
        writer.writeClassInfo(output)

    override fun writeObject(obj: Any, data: Data, type: Type, output: SerializationOutput, context: SerializationContext, debugIndent: Int) =
        writer.writeObject(obj, data, type, output, context, debugIndent)

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput, context: SerializationContext): Any =
        throw UnsupportedOperationException("Cannot deserialize abstract type ${type.typeName}")
}

class EvolutionObjectSerializer(
        override val type: Type,
        override val typeDescriptor: Symbol,
        override val propertySerializers: Map<PropertyName, ComposableTypePropertySerializer>,
        private val reader: ComposableObjectReader): ObjectSerializer {

    companion object {
        fun make(localTypeInformation: LocalTypeInformation.Composable, remoteTypeInformation: RemoteTypeInformation.Composable, constructor: LocalConstructorInformation,
                 properties: Map<String, LocalPropertyInformation>, classLoader: ClassLoader): EvolutionObjectSerializer {
            val propertySerializers = makePropertySerializers(properties, remoteTypeInformation.properties, classLoader)
            val reader = ComposableObjectReader(
                    localTypeInformation.typeIdentifier,
                    propertySerializers,
                    EvolutionObjectBuilder.makeProvider(localTypeInformation.typeIdentifier, constructor, properties, remoteTypeInformation.properties.keys.sorted()))

            return EvolutionObjectSerializer(
                    localTypeInformation.observedType,
                    Symbol.valueOf(remoteTypeInformation.typeDescriptor),
                    propertySerializers,
                    reader)
        }

        private fun makePropertySerializers(localProperties: Map<String, LocalPropertyInformation>,
                                            remoteProperties: Map<String, RemotePropertyInformation>,
                                            classLoader: ClassLoader): Map<String, ComposableTypePropertySerializer> =
                remoteProperties.mapValues { (name, property) ->
                    val localProperty = localProperties[name]
                    val isCalculated = localProperty?.isCalculated ?: false
                    val type = localProperty?.type?.observedType ?: property.type.typeIdentifier.getLocalType(classLoader)
                    ComposableTypePropertySerializer.makeForEvolution(name, isCalculated, property.type.typeIdentifier, type)
                }
    }

    override val fields: List<Field> get() = emptyList()

    override fun writeClassInfo(output: SerializationOutput) =
            throw UnsupportedOperationException("Evolved types cannot be written")

    override fun writeObject(obj: Any, data: Data, type: Type, output: SerializationOutput, context: SerializationContext, debugIndent: Int) =
            throw UnsupportedOperationException("Evolved types cannot be written")

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput, context: SerializationContext): Any =
            reader.readObject(obj, schemas, input, context)

}