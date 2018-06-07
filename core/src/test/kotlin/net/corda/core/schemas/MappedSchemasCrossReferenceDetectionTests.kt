package net.corda.core.schemas

import net.corda.core.schemas.MappedSchemaValidator.fieldsFromOtherMappedSchema
import net.corda.core.schemas.MappedSchemaValidator.methodsFromOtherMappedSchema
import net.corda.finance.schemas.CashSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import javax.persistence.*

class MappedSchemasCrossReferenceDetectionTests {

    object GoodSchema : MappedSchema(schemaFamily = CashSchema.javaClass, version = 1, mappedTypes = listOf(State::class.java)) {
        @Entity
        class State(
                @Column
                var id: String
        ) : PersistentState()
    }

    object BadSchema : MappedSchema(schemaFamily = CashSchema.javaClass, version = 1, mappedTypes = listOf(State::class.java)) {
        @Entity
        class State(
                @Column
                var id: String,

                @JoinColumns(JoinColumn(name = "itid"), JoinColumn(name = "outid"))
                @OneToOne
                @MapsId
                var other: GoodSchema.State
        ) : PersistentState()
    }

    object TrickySchema : MappedSchema(schemaFamily = CashSchema.javaClass, version = 1, mappedTypes = listOf(State::class.java)) {
        @Entity
        class State(
                @Column
                var id: String,

                //the field is from other schema bu it's not persisted one (no JPA annotation)
                var other: GoodSchema.State
        ) : PersistentState()
    }

    object PoliteSchema : MappedSchema(schemaFamily = CashSchema.javaClass, version = 1, mappedTypes = listOf(State::class.java)) {
        @Entity
        class State(
                @Column
                var id: String,

                @Transient
                var other: GoodSchema.State
        ) : PersistentState()
    }

    @Test
    fun `no cross reference to other schema`() {
        assertThat(fieldsFromOtherMappedSchema(GoodSchema)).isEmpty()
        assertThat(methodsFromOtherMappedSchema(GoodSchema)).isEmpty()
    }

    @Test
    fun `cross reference to other schema is detected`() {
        assertThat(fieldsFromOtherMappedSchema(BadSchema)).isNotEmpty
        assertThat(methodsFromOtherMappedSchema(BadSchema)).isEmpty()
    }

    @Test
    fun `cross reference via non JPA field is allowed`() {
        assertThat(fieldsFromOtherMappedSchema(TrickySchema)).isEmpty()
        assertThat(methodsFromOtherMappedSchema(TrickySchema)).isEmpty()
    }

    @Test
    fun `cross reference via transient field is allowed`() {
        assertThat(fieldsFromOtherMappedSchema(PoliteSchema)).isEmpty()
        assertThat(methodsFromOtherMappedSchema(PoliteSchema)).isEmpty()
    }

    @Test
    fun `no cross reference to other schema java`() {
        assertThat(fieldsFromOtherMappedSchema(MappedSchemas.GoodSchemaJava.getInstance())).isEmpty()
        assertThat(methodsFromOtherMappedSchema(MappedSchemas.GoodSchemaJava.getInstance())).isEmpty()
    }

    @Test
    fun `cross reference to other schema is detected java`() {
        assertThat(fieldsFromOtherMappedSchema(MappedSchemas.BadSchemaJava.getInstance())).isEmpty()
        assertThat(methodsFromOtherMappedSchema(MappedSchemas.BadSchemaJava.getInstance())).isNotEmpty
    }

    @Test
    fun `cross reference to other schema via field is detected java`() {
        assertThat(fieldsFromOtherMappedSchema(MappedSchemas.BadSchemaNoGetterJava.getInstance())).isNotEmpty
        assertThat(methodsFromOtherMappedSchema(MappedSchemas.BadSchemaNoGetterJava.getInstance())).isEmpty()
    }

    @Test
    fun `cross reference via non JPA field is allowed java`() {
        assertThat(fieldsFromOtherMappedSchema(MappedSchemas.TrickySchemaJava.getInstance())).isEmpty()
        assertThat(methodsFromOtherMappedSchema(MappedSchemas.TrickySchemaJava.getInstance())).isEmpty()
    }

    @Test
    fun `cross reference via transient field is allowed java`() {
        assertThat(fieldsFromOtherMappedSchema(MappedSchemas.PoliteSchemaJava.getInstance())).isEmpty()
        assertThat(methodsFromOtherMappedSchema(MappedSchemas.PoliteSchemaJava.getInstance())).isEmpty()
    }
}