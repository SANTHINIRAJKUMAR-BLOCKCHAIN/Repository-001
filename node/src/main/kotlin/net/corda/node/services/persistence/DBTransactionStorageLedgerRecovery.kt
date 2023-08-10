package net.corda.node.services.persistence

import net.corda.core.crypto.SecureHash
import net.corda.core.flows.RecoveryTimeWindow
import net.corda.core.flows.TransactionMetadata
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.NamedCacheFactory
import net.corda.core.node.StatesToRecord
import net.corda.core.node.services.vault.Sort
import net.corda.core.serialization.CordaSerializable
import net.corda.node.CordaClock
import net.corda.node.services.network.PersistentPartyInfoCache
import net.corda.nodeapi.internal.cryptoservice.CryptoService
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.NODE_DATABASE_PREFIX
import org.hibernate.annotations.Immutable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.persistence.Column
import javax.persistence.Embeddable
import javax.persistence.EmbeddedId
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Lob
import javax.persistence.Table
import javax.persistence.criteria.Predicate

class DBTransactionStorageLedgerRecovery(private val database: CordaPersistence, cacheFactory: NamedCacheFactory,
                                         val clock: CordaClock,
                                         private val cryptoService: CryptoService,
                                         private val partyInfoCache: PersistentPartyInfoCache) : DBTransactionStorage(database, cacheFactory, clock) {
    @Embeddable
    @Immutable
    data class PersistentKey(
            @Column(name = "sequence_number", nullable = false)
            var sequenceNumber: Long,

            @Column(name = "timestamp", nullable = false)
            var timestamp: Instant
    ) : Serializable {
        constructor(key: Key) : this(key.sequenceNumber, key.timestamp)
    }

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}sender_distribution_records")
    data class DBSenderDistributionRecord(
            @EmbeddedId
            var compositeKey: PersistentKey,

            @Column(name = "transaction_id", length = 144, nullable = false)
            var txId: String,

            /** PartyId of flow peer **/
            @Column(name = "receiver_party_id", nullable = false)
            val receiverPartyId: Long,

            /** states to record: NONE, ALL_VISIBLE, ONLY_RELEVANT */
            @Column(name = "states_to_record", nullable = false)
            var statesToRecord: StatesToRecord

    ) {
        fun toSenderDistributionRecord() =
            SenderDistributionRecord(
                    SecureHash.parse(this.txId),
                    this.receiverPartyId,
                    this.statesToRecord,
                    this.compositeKey.timestamp
            )
    }

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}receiver_distribution_records")
    data class DBReceiverDistributionRecord(
            @EmbeddedId
            var compositeKey: PersistentKey,

            @Column(name = "transaction_id", length = 144, nullable = false)
            var txId: String,

            /** PartyId of flow initiator **/
            @Column(name = "sender_party_id", nullable = true)
            val senderPartyId: Long,

            /** Encrypted recovery information for sole use by Sender **/
            @Lob
            @Column(name = "distribution_list", nullable = false)
            val distributionList: ByteArray,

            /** states to record: NONE, ALL_VISIBLE, ONLY_RELEVANT */
            @Column(name = "receiver_states_to_record", nullable = false)
            val receiverStatesToRecord: StatesToRecord
) {
        constructor(key: Key, txId: SecureHash, initiatorPartyId: Long, encryptedDistributionList: ByteArray, receiverStatesToRecord: StatesToRecord) :
            this(PersistentKey(key),
                 txId = txId.toString(),
                 senderPartyId = initiatorPartyId,
                 distributionList = encryptedDistributionList,
                 receiverStatesToRecord = receiverStatesToRecord
            )

        fun toReceiverDistributionRecord(cryptoService: CryptoService): ReceiverDistributionRecord {
            val hashedDL = HashedDistributionList.deserialize(cryptoService.decrypt(this.distributionList))
            return ReceiverDistributionRecord(
                    SecureHash.parse(this.txId),
                    this.senderPartyId,
                    hashedDL.peerHashToStatesToRecord,
                    this.receiverStatesToRecord,
                    hashedDL.senderStatesToRecord,
                    this.compositeKey.timestamp
            )
        }
    }

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}recovery_party_info")
    data class DBRecoveryPartyInfo(
            @Id
            /** CordaX500Name hashCode() **/
            @Column(name = "party_id", nullable = false)
            var partyId: Long,

            /** CordaX500Name of party **/
            @Column(name = "party_name", nullable = false)
            val partyName: String
    )

    class Key(
            val timestamp: Instant,
            val sequenceNumber: Long = nextSequenceNumber.andIncrement
    ) {
        companion object {
            private val nextSequenceNumber = AtomicLong()
        }
    }

    override fun addSenderTransactionRecoveryMetadata(id: SecureHash, metadata: TransactionMetadata): ByteArray {
        return database.transaction {
            val senderRecordingTimestamp = clock.instant()
            metadata.distributionList.peersToStatesToRecord.forEach { (peer, _) ->
                val senderDistributionRecord = DBSenderDistributionRecord(PersistentKey(Key(senderRecordingTimestamp)),
                        id.toString(),
                        partyInfoCache.getPartyIdByCordaX500Name(peer),
                        metadata.distributionList.senderStatesToRecord)
                session.save(senderDistributionRecord)
            }
            val hashedPeersToStatesToRecord = metadata.distributionList.peersToStatesToRecord.map { (peer, statesToRecord) ->
                partyInfoCache.getPartyIdByCordaX500Name(peer) to statesToRecord }.toMap()
            val hashedDistributionList = HashedDistributionList(metadata.distributionList.senderStatesToRecord, hashedPeersToStatesToRecord, senderRecordingTimestamp)
            cryptoService.encrypt(hashedDistributionList.serialize())
        }
    }

    override fun addReceiverTransactionRecoveryMetadata(id: SecureHash, sender: CordaX500Name, receiver: CordaX500Name, receiverStatesToRecord: StatesToRecord, encryptedDistributionList: ByteArray) {
        val senderRecordedTimestamp = HashedDistributionList.deserialize(cryptoService.decrypt(encryptedDistributionList)).senderRecordedTimestamp
        database.transaction {
            val receiverDistributionRecord =
                    DBReceiverDistributionRecord(Key(senderRecordedTimestamp),
                            id,
                            partyInfoCache.getPartyIdByCordaX500Name(sender),
                            encryptedDistributionList,
                            receiverStatesToRecord)
            session.save(receiverDistributionRecord)
        }
    }

    override fun removeUnnotarisedTransaction(id: SecureHash): Boolean {
        return database.transaction {
            super.removeUnnotarisedTransaction(id)
            val criteriaBuilder = session.criteriaBuilder
            val deleteSenderDistributionRecords = criteriaBuilder.createCriteriaDelete(DBSenderDistributionRecord::class.java)
            val root = deleteSenderDistributionRecords.from(DBSenderDistributionRecord::class.java)
            deleteSenderDistributionRecords.where(criteriaBuilder.equal(root.get<String>(DBSenderDistributionRecord::txId.name), id.toString()))
            val deletedSenderDistributionRecords = session.createQuery(deleteSenderDistributionRecords).executeUpdate() != 0
            val deleteReceiverDistributionRecords = criteriaBuilder.createCriteriaDelete(DBReceiverDistributionRecord::class.java)
            val rootReceiverDistributionRecord = deleteReceiverDistributionRecords.from(DBReceiverDistributionRecord::class.java)
            deleteReceiverDistributionRecords.where(criteriaBuilder.equal(rootReceiverDistributionRecord.get<String>(DBReceiverDistributionRecord::txId.name), id.toString()))
            val deletedReceiverDistributionRecords = session.createQuery(deleteReceiverDistributionRecords).executeUpdate() != 0
            deletedSenderDistributionRecords || deletedReceiverDistributionRecords
        }
    }

    fun queryDistributionRecords(timeWindow: RecoveryTimeWindow,
                               recordType: DistributionRecordType = DistributionRecordType.ALL,
                               excludingTxnIds: Set<SecureHash>? = null,
                               orderByTimestamp: Sort.Direction? = null
    ): List<DistributionRecord> {
        return when(recordType) {
            DistributionRecordType.SENDER ->
                querySenderDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp)
            DistributionRecordType.RECEIVER ->
                queryReceiverDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp)
            DistributionRecordType.ALL ->
                querySenderDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp).plus(
                        queryReceiverDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp)
                )
        }
    }

    @Suppress("SpreadOperator")
    fun querySenderDistributionRecords(timeWindow: RecoveryTimeWindow,
                                       peers: Set<CordaX500Name> = emptySet(),
                                       excludingTxnIds: Set<SecureHash>? = null,
                                       orderByTimestamp: Sort.Direction? = null
                             ): List<SenderDistributionRecord> {
        return database.transaction {
            val criteriaBuilder = session.criteriaBuilder
            val criteriaQuery = criteriaBuilder.createQuery(DBSenderDistributionRecord::class.java)
            val txnMetadata = criteriaQuery.from(DBSenderDistributionRecord::class.java)
            val predicates = mutableListOf<Predicate>()
            val compositeKey = txnMetadata.get<PersistentKey>("compositeKey")
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.fromTime))
            predicates.add(criteriaBuilder.and(criteriaBuilder.lessThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.untilTime)))
            excludingTxnIds?.let { excludingTxnIds ->
                predicates.add(criteriaBuilder.and(criteriaBuilder.notEqual(txnMetadata.get<String>(DBSenderDistributionRecord::txId.name),
                        excludingTxnIds.map { it.toString() })))
            }
            if (peers.isNotEmpty()) {
                val peerPartyIds = peers.map { partyInfoCache.getPartyIdByCordaX500Name(it) }
                predicates.add(criteriaBuilder.and(txnMetadata.get<Long>(DBSenderDistributionRecord::receiverPartyId.name).`in`(peerPartyIds)))
            }
            criteriaQuery.where(*predicates.toTypedArray())
            // optionally order by timestamp
            orderByTimestamp?.let {
                val orderCriteria =
                        when (orderByTimestamp) {
                            // when adding column position of 'group by' shift in case columns were removed
                            Sort.Direction.ASC -> criteriaBuilder.asc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                            Sort.Direction.DESC -> criteriaBuilder.desc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                        }
                criteriaQuery.orderBy(orderCriteria)
            }
            val results = session.createQuery(criteriaQuery).stream()
            results.map { it.toSenderDistributionRecord() }.toList()
        }
    }

    @Suppress("SpreadOperator")
    fun queryReceiverDistributionRecords(timeWindow: RecoveryTimeWindow,
                                       initiators: Set<CordaX500Name> = emptySet(),
                                       excludingTxnIds: Set<SecureHash>? = null,
                                       orderByTimestamp: Sort.Direction? = null
    ): List<ReceiverDistributionRecord> {
        return database.transaction {
            val criteriaBuilder = session.criteriaBuilder
            val criteriaQuery = criteriaBuilder.createQuery(DBReceiverDistributionRecord::class.java)
            val txnMetadata = criteriaQuery.from(DBReceiverDistributionRecord::class.java)
            val predicates = mutableListOf<Predicate>()
            val compositeKey = txnMetadata.get<PersistentKey>("compositeKey")
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.fromTime))
            predicates.add(criteriaBuilder.and(criteriaBuilder.lessThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.untilTime)))
            excludingTxnIds?.let { excludingTxnIds ->
                predicates.add(criteriaBuilder.and(criteriaBuilder.notEqual(txnMetadata.get<String>(DBReceiverDistributionRecord::txId.name),
                        excludingTxnIds.map { it.toString() })))
            }
            if (initiators.isNotEmpty()) {
                val initiatorPartyIds = initiators.map { partyInfoCache.getPartyIdByCordaX500Name(it) }
                predicates.add(criteriaBuilder.and(txnMetadata.get<Long>(DBReceiverDistributionRecord::senderPartyId.name).`in`(initiatorPartyIds)))
            }
            criteriaQuery.where(*predicates.toTypedArray())
            // optionally order by timestamp
            orderByTimestamp?.let {
                val orderCriteria =
                        when (orderByTimestamp) {
                            // when adding column position of 'group by' shift in case columns were removed
                            Sort.Direction.ASC -> criteriaBuilder.asc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                            Sort.Direction.DESC -> criteriaBuilder.desc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                        }
                criteriaQuery.orderBy(orderCriteria)
            }
            val results = session.createQuery(criteriaQuery).stream()
            results.map { it.toReceiverDistributionRecord(cryptoService) }.toList()
        }
    }
}

// TO DO: https://r3-cev.atlassian.net/browse/ENT-9876
private fun CryptoService.decrypt(bytes: ByteArray): ByteArray {
    return bytes
}

// TO DO: https://r3-cev.atlassian.net/browse/ENT-9876
fun CryptoService.encrypt(bytes: ByteArray): ByteArray {
    return bytes
}

@CordaSerializable
open class DistributionRecord(
        open val txId: SecureHash,
        open val statesToRecord: StatesToRecord,
        open val timestamp: Instant
)

@CordaSerializable
data class SenderDistributionRecord(
        override val txId: SecureHash,
        val peerPartyId: Long,     // CordaX500Name hashCode()
        override val statesToRecord: StatesToRecord,
        override val timestamp: Instant
) : DistributionRecord(txId, statesToRecord, timestamp)

@CordaSerializable
data class ReceiverDistributionRecord(
        override val txId: SecureHash,
        val initiatorPartyId: Long,     // CordaX500Name hashCode()
        val peersToStatesToRecord: Map<Long, StatesToRecord>,   // CordaX500Name hashCode() -> StatesToRecord
        override val statesToRecord: StatesToRecord,
        val senderStatesToRecord: StatesToRecord,
        override val timestamp: Instant
) : DistributionRecord(txId, statesToRecord, timestamp)

@CordaSerializable
enum class DistributionRecordType {
    SENDER, RECEIVER, ALL
}

@CordaSerializable
data class HashedDistributionList(
        val senderStatesToRecord: StatesToRecord,
        val peerHashToStatesToRecord: Map<Long, StatesToRecord>,
        val senderRecordedTimestamp: Instant
) {
    fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.use {
            out.writeByte(SERIALIZER_VERSION_ID)
            out.writeByte(senderStatesToRecord.ordinal)
            out.writeInt(peerHashToStatesToRecord.size)
            for(entry in peerHashToStatesToRecord) {
                out.writeLong(entry.key)
                out.writeByte(entry.value.ordinal)
            }
            out.writeLong(senderRecordedTimestamp.toEpochMilli())
            out.flush()
            return baos.toByteArray()
        }
    }
    companion object {
        const val SERIALIZER_VERSION_ID = 1
        fun deserialize(bytes: ByteArray): HashedDistributionList {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            input.use {
                assert(input.readByte().toInt() == SERIALIZER_VERSION_ID) { "Serialization version conflict." }
                val senderStatesToRecord = StatesToRecord.values()[input.readByte().toInt()]
                val numPeerHashToStatesToRecords = input.readInt()
                val peerHashToStatesToRecord = mutableMapOf<Long, StatesToRecord>()
                repeat (numPeerHashToStatesToRecords) {
                    peerHashToStatesToRecord[input.readLong()] = StatesToRecord.values()[input.readByte().toInt()]
                }
                val senderRecordedTimestamp = Instant.ofEpochMilli(input.readLong())
                return HashedDistributionList(senderStatesToRecord, peerHashToStatesToRecord, senderRecordedTimestamp)
            }
        }
    }
}


