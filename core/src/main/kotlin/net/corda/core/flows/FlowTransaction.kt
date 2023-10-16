package net.corda.core.flows

import net.corda.core.contracts.NamedByHash
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.StatesToRecord
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.OpaqueBytes
import java.time.Instant

/**
 * Flow data object representing key information required for recovery.
 */

@CordaSerializable
data class FlowTransactionInfo(
        val stateMachineRunId: StateMachineRunId,
        val txId: String,
        val status: TransactionStatus,
        val timestamp: Instant,
        val metadata: TransactionMetadata?
) {
    fun isInitiator(myCordaX500Name: CordaX500Name) =
            this.metadata?.initiator == myCordaX500Name
}

@CordaSerializable
data class TransactionMetadata(
        val initiator: CordaX500Name,
        val distributionList: DistributionList
)

@CordaSerializable
sealed class DistributionList {

    @CordaSerializable
    data class SenderDistributionList(
            val senderStatesToRecord: StatesToRecord,
            val peersToStatesToRecord: Map<CordaX500Name, StatesToRecord>
    ) : DistributionList()

    @CordaSerializable
    data class ReceiverDistributionList(
            val opaqueData: ByteArray,  // decipherable only by sender
            val receiverStatesToRecord: StatesToRecord  // inferred or actual
    ) : DistributionList()
}

@CordaSerializable
class DistributionRecords(
        val senderRecords: List<SenderDistributionRecord> = emptyList(),
        val receiverRecords: List<ReceiverDistributionRecord> = emptyList()
) {
    val size = senderRecords.size + receiverRecords.size
}

@CordaSerializable
abstract class DistributionRecord : NamedByHash {
    abstract val txId: SecureHash
    abstract val peerPartyId: SecureHash
    abstract val timestamp: Instant
    abstract val timestampDiscriminator: Int
}

@CordaSerializable
data class SenderDistributionRecord(
        override val txId: SecureHash,
        override val peerPartyId: SecureHash,
        override val timestamp: Instant,
        override val timestampDiscriminator: Int,
        val senderStatesToRecord: StatesToRecord,
        val receiverStatesToRecord: StatesToRecord
) : DistributionRecord() {
    override val id: SecureHash
        get() = this.txId
}

@CordaSerializable
data class ReceiverDistributionRecord(
        override val txId: SecureHash,
        override val peerPartyId: SecureHash,
        override val timestamp: Instant,
        override val timestampDiscriminator: Int,
        val encryptedDistributionList: OpaqueBytes,
        val receiverStatesToRecord: StatesToRecord
) : DistributionRecord() {
    override val id: SecureHash
        get() = this.txId
}

@CordaSerializable
enum class DistributionRecordType {
    SENDER, RECEIVER, ALL
}
@CordaSerializable
data class DistributionRecordKey(
        val txnId: SecureHash,
        val timestamp: Instant,
        val timestampDiscriminator: Int
)

@CordaSerializable
enum class TransactionStatus {
    UNVERIFIED,
    VERIFIED,
    IN_FLIGHT;
}

@CordaSerializable
data class RecoveryTimeWindow(val fromTime: Instant, val untilTime: Instant = Instant.now()) {

    init {
        if (untilTime < fromTime) {
            throw IllegalArgumentException("$fromTime must be before $untilTime")
        }
    }

    companion object {
        @JvmStatic
        fun between(fromTime: Instant, untilTime: Instant): RecoveryTimeWindow {
            return RecoveryTimeWindow(fromTime, untilTime)
        }

        @JvmStatic
        fun fromOnly(fromTime: Instant): RecoveryTimeWindow {
            return RecoveryTimeWindow(fromTime = fromTime)
        }

        @JvmStatic
        fun untilOnly(untilTime: Instant): RecoveryTimeWindow {
            return RecoveryTimeWindow(fromTime = Instant.EPOCH, untilTime = untilTime)
        }
    }
}