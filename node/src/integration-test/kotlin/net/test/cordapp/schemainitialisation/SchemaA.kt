package net.test.cordapp.schemainitialisation

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.Contract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.testMessage.MessageSchemaV1
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

object SchemaA
object SchemaAV1 : MappedSchema(
        schemaFamily = SchemaA.javaClass,
        version = 1,
        mappedTypes = listOf(SchemaAV1.PersistentMessageA::class.java)) {

    @Entity
    @Table(name = "messages")
    class PersistentMessageA(
            @Column(name = "message_by", nullable = false)
            var by: String,

            @Column(name = "message_value", nullable = false)
            var value: String
    ) : PersistentState()
}

@CordaSerializable
data class MessageA(val value: String)

@BelongsToContract(MessageAContract::class)
data class MessageAState(val message: MessageA, val by: Party, override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState,
        QueryableState {
    override val participants: List<AbstractParty> = listOf(by)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is SchemaAV1 -> MessageSchemaV1.PersistentMessage(
                    by = by.name.toString(),
                    value = message.value
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(MessageSchemaV1)
}

open class MessageAContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands.Send>()
        requireThat {
            // Generic constraints around the IOU transaction.
            "No inputs should be consumed when sending a message." using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val out = tx.outputsOfType<MessageAState>().single()
            "Message sender must sign." using (command.signers.containsAll(out.participants.map { it.owningKey }))

            "Message value must not be empty." using (out.message.value.isNotBlank())
        }
    }

    interface Commands : CommandData {
        class Send : Commands
    }
}