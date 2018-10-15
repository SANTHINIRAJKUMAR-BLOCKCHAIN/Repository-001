package net.corda.node.services.config.parsing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class ConfigSchemaTest {

    @Test
    fun validation_with_nested_properties() {

        val prop1 = "prop1"
        val prop1Value = "value1"

        val prop2 = "prop2"
        val prop2Value = 3L

        val prop3 = "prop3"
        val prop4 = "prop4"
        val prop4Value = true
        val prop5 = "prop5"
        val prop5Value = -17.3
        val prop3Value = configObject(prop4 to prop4Value, prop5 to prop5Value)

        val configuration = configObject(prop1 to prop1Value, prop2 to prop2Value, prop3 to prop3Value).toConfig()
        println(configuration.serialize())

        val fooConfigSchema = ConfigSchema.withProperties(name = "Foo") { setOf(boolean("prop4"), double("prop5")) }
        val barConfigSchema = ConfigSchema.withProperties(name = "Bar") { setOf(string(prop1), long(prop2), value("prop3", fooConfigSchema)) }

        val errors = barConfigSchema.validate(configuration)
        println(barConfigSchema.description())

        assertThat(errors).isEmpty()
        assertThat(barConfigSchema.isValid(configuration)).isTrue()
        assertThatCode { barConfigSchema.rejectIfInvalid(configuration) { _ -> IllegalArgumentException() } }.doesNotThrowAnyException()
    }

    @Test
    fun validation_with_unknown_properties_strict() {

        val prop1 = "prop1"
        val prop1Value = "value1"

        val prop2 = "prop2"
        val prop2Value = 3L

        val prop3 = "prop3"
        val prop4 = "prop4"
        val prop4Value = true
        val prop5 = "prop5"
        val prop5Value = -17.3
        // Here "prop6" is not known to the schema.
        val prop3Value = configObject(prop4 to prop4Value, "prop6" to "value6", prop5 to prop5Value)

        // Here "prop4" is not known to the schema.
        val configuration = configObject(prop1 to prop1Value, prop2 to prop2Value, prop3 to prop3Value, "prop4" to "value4").toConfig()
        println(configuration.serialize())

        val fooConfigSchema = ConfigSchema.withProperties(true) { setOf(boolean("prop4"), double("prop5")) }
        val barConfigSchema = ConfigSchema.withProperties(true) { setOf(string(prop1), long(prop2), value("prop3", fooConfigSchema)) }

        val errors = barConfigSchema.validate(configuration)

        assertThat(errors).hasSize(2)
        assertThat(barConfigSchema.isValid(configuration)).isFalse()
        assertThatThrownBy { barConfigSchema.rejectIfInvalid(configuration) { _ -> IllegalArgumentException() } }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(errors.filter { error -> error.keyName == "prop4" }).hasSize(1)
        assertThat(errors.filter { error -> error.keyName == "prop6" }).hasSize(1)
    }

    @Test
    fun validation_with_unknown_properties_non_strict() {

        val prop1 = "prop1"
        val prop1Value = "value1"

        val prop2 = "prop2"
        val prop2Value = 3L

        val prop3 = "prop3"
        val prop4 = "prop4"
        val prop4Value = true
        val prop5 = "prop5"
        val prop5Value = -17.3
        // Here "prop6" is not known to the schema, but it is not in strict mode.
        val prop3Value = configObject(prop4 to prop4Value, "prop6" to "value6", prop5 to prop5Value)

        // Here "prop4" is not known to the schema, but it is not in strict mode.
        val configuration = configObject(prop1 to prop1Value, prop2 to prop2Value, prop3 to prop3Value, "prop4" to "value4").toConfig()
        println(configuration.serialize())

        val fooConfigSchema = ConfigSchema.withProperties { setOf(boolean("prop4"), double("prop5")) }
        val barConfigSchema = ConfigSchema.withProperties { setOf(string(prop1), long(prop2), value("prop3", fooConfigSchema)) }

        val errors = barConfigSchema.validate(configuration)

        assertThat(errors).isEmpty()
        assertThat(barConfigSchema.isValid(configuration)).isTrue()
        assertThatCode { barConfigSchema.rejectIfInvalid(configuration) { _ -> IllegalArgumentException() } }.doesNotThrowAnyException()
    }

    @Test
    fun validation_with_wrong_nested_properties() {

        val prop1 = "prop1"
        val prop1Value = "value1"

        val prop2 = "prop2"
        // This value is wrong, should be an Int.
        val prop2Value = false

        val prop3 = "prop3"
        val prop4 = "prop4"
        // This value is wrong, should be a Boolean.
        val prop4Value = 44444
        val prop5 = "prop5"
        val prop5Value = -17.3
        val prop3Value = configObject(prop4 to prop4Value, prop5 to prop5Value)

        val configuration = configObject(prop1 to prop1Value, prop2 to prop2Value, prop3 to prop3Value).toConfig()
        println(configuration.serialize())

        val fooConfigSchema = ConfigSchema.withProperties { setOf(boolean("prop4"), double("prop5")) }
        val barConfigSchema = ConfigSchema.withProperties { setOf(string(prop1), long(prop2), value("prop3", fooConfigSchema)) }

        val errors = barConfigSchema.validate(configuration)
        errors.forEach(::println)

        assertThat(errors).hasSize(2)
        assertThat(barConfigSchema.isValid(configuration)).isFalse()
        assertThatThrownBy { barConfigSchema.rejectIfInvalid(configuration) { _ -> IllegalArgumentException() } }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private interface BarConfig {

        val prop1: String
        val prop2: Int
        val prop3: FooConfig
    }

    private interface FooConfig {

        val prop4: Boolean
        val prop5: Double
    }
}