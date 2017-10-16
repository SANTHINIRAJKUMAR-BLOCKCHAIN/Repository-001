package net.corda.nodeapi

import net.corda.core.serialization.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.utilities.sequence
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.client.ClientMessage
import org.apache.activemq.artemis.reader.MessageUtil

object VerifierApi {
    const val VERIFIER_USERNAME = "SystemUsers/Verifier"
    const val VERIFICATION_REQUESTS_QUEUE_NAME = "verifier.requests"
    const val VERIFICATION_RESPONSES_QUEUE_NAME_PREFIX = "verifier.responses"
    private const val VERIFICATION_ID_FIELD_NAME = "id"
    private const val RESULT_EXCEPTION_FIELD_NAME = "result-exception"

    data class VerificationRequest(
            val verificationId: Long,
            val transaction: LedgerTransaction,
            val responseAddress: SimpleString
    ) {
        companion object {
            fun fromClientMessage(message: ClientMessage): Pair<VerificationRequest, VersionHeader> {
                val bytes = ByteArray(message.bodySize).apply { message.bodyBuffer.readBytes(this) }
                val bytesSequence = bytes.sequence()
                val (transaction, versionHeader) = bytesSequence.deserializeWithVersionHeader<LedgerTransaction>()
                val request = VerificationRequest(
                        message.getLongProperty(VERIFICATION_ID_FIELD_NAME),
                        transaction,
                        MessageUtil.getJMSReplyTo(message))
                return (request to versionHeader)
            }
        }

        fun writeToClientMessage(message: ClientMessage) {
            message.putLongProperty(VERIFICATION_ID_FIELD_NAME, verificationId)
            message.writeBodyBufferBytes(transaction.serialize().bytes)
            MessageUtil.setJMSReplyTo(message, responseAddress)
        }
    }

    data class VerificationResponse(
            val verificationId: Long,
            val exception: Throwable?
    ) {
        companion object {
            fun fromClientMessage(message: ClientMessage): VerificationResponse {
                return VerificationResponse(
                        message.getLongProperty(VERIFICATION_ID_FIELD_NAME),
                        message.getBytesProperty(RESULT_EXCEPTION_FIELD_NAME)?.deserialize()
                )
            }
        }

        fun writeToClientMessage(message: ClientMessage, versionHeader: VersionHeader) {
            message.putLongProperty(VERIFICATION_ID_FIELD_NAME, verificationId)
            if (exception != null) {
                message.putBytesProperty(RESULT_EXCEPTION_FIELD_NAME,
                        exception.serialize(context = SerializationFactory.defaultFactory.defaultContext.withPreferredSerializationVersion(versionHeader)).bytes)
            }
        }
    }
}
