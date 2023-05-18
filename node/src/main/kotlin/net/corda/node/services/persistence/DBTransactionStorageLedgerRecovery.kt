package net.corda.node.services.persistence

import net.corda.core.crypto.SecureHash
import net.corda.core.flows.RecoveryTimeWindow
import net.corda.core.flows.TransactionMetadata
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.NamedCacheFactory
import net.corda.core.internal.ThreadBox
import net.corda.core.node.StatesToRecord
import net.corda.core.node.services.vault.Sort
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.node.CordaClock
import net.corda.node.services.network.PersistentPartyInfoCache
import net.corda.node.utilities.AppendOnlyPersistentMap
import net.corda.node.utilities.AppendOnlyPersistentMapBase
import net.corda.nodeapi.internal.cryptoservice.CryptoService
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.NODE_DATABASE_PREFIX
import net.corda.serialization.internal.CordaSerializationEncoding
import org.hibernate.annotations.Immutable
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
import kotlin.streams.toList

class DBTransactionStorageLedgerRecovery(private val database: CordaPersistence, cacheFactory: NamedCacheFactory,
                                         val clock: CordaClock,
                                         private val cryptoService: CryptoService,
                                         private val partyInfoCache: PersistentPartyInfoCache) : DBTransactionStorage(database, cacheFactory, clock) {
    internal companion object {
        private val logger = contextLogger()
    }

    @Embeddable
    @Immutable
    data class PersistentKey(
            @Column(name = "sequence_number", nullable = false)
            var sequenceNumber: Long,

            @Column(name = "timestamp", nullable = false)
            var timestamp: Instant
    ) : Serializable {
        constructor(key: TxRecoveryCacheValue.Key) : this(key.sequenceNumber, key.timestamp)
    }

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}sender_distribution_records")
    data class DBSenderDistributionRecord(
            @EmbeddedId
            var compositeKey: PersistentKey,

            @Column(name = "tx_id", length = 144, nullable = false)
            var txId: String,

            /** PartyId of flow peer **/
            @Column(name = "receiver_party_id", nullable = false)
            val receiverPartyId: Long,

            /** states to record: NONE, ALL_VISIBLE, ONLY_RELEVANT */
            @Column(name = "states_to_record", nullable = false)
            var statesToRecord: StatesToRecord

    ) {
        constructor(key: TxRecoveryCacheValue.Key, txId: SecureHash, peerPartyId: Long, statesToRecord: StatesToRecord) :
            this(PersistentKey(key),
                 txId = txId.toString(),
                 receiverPartyId = peerPartyId,
                 statesToRecord = statesToRecord
        )

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

            @Column(name = "tx_id", length = 144, nullable = false)
            var txId: String,

            /** PartyId of flow initiator **/
            @Column(name = "sender_party_id", nullable = true)
            val senderPartyId: Long,

            /** Encrypted partyId's of flow peers **/
            @Lob
            @Column(name = "receiver_party_ids", nullable = false)
            val receiverPartyIds: ByteArray,

            /** states to record: NONE, ALL_VISIBLE, ONLY_RELEVANT */
            @Column(name = "states_to_record", nullable = false)
            var statesToRecord: StatesToRecord
    ) {
        constructor(key: TxRecoveryCacheValue.Key, txId: SecureHash, initiatorPartyId: Long, peerPartyIds: Set<Long>, statesToRecord: StatesToRecord, cryptoService: CryptoService) :
            this(PersistentKey(key),
                 txId = txId.toString(),
                 senderPartyId = initiatorPartyId,
                 receiverPartyIds = cryptoService.encrypt(peerPartyIds.serialize(context = contextToUse().withEncoding(CordaSerializationEncoding.SNAPPY)).bytes),
                 statesToRecord = statesToRecord
            )

        fun toReceiverDistributionRecord(cryptoService: CryptoService) =
            ReceiverDistributionRecord(
                    SecureHash.parse(this.txId),
                    this.senderPartyId,
                    cryptoService.decrypt(this.receiverPartyIds).deserialize(context = contextToUse()),
                    this.statesToRecord,
                    this.compositeKey.timestamp
            )
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

    private val senderDistributionRecordStorage = ThreadBox(createSenderDistributionRecoveryMap(cacheFactory))
    private val receiverDistributionRecordStorage = ThreadBox(createReceiverDistributionRecoveryMap(cacheFactory))

    class TxRecoveryCacheValue(
            val key: Key,
            val metadata: DistributionRecord) {

        companion object {
            private val nextSequenceNumber = AtomicLong()
        }

        class Key(
                val timestamp: Instant,
                val sequenceNumber: Long = nextSequenceNumber.andIncrement
        )
    }

    private fun createSenderDistributionRecoveryMap(cacheFactory: NamedCacheFactory)
            : AppendOnlyPersistentMapBase<TxRecoveryCacheValue.Key, TxRecoveryCacheValue, DBSenderDistributionRecord, PersistentKey> {
        return AppendOnlyPersistentMap(
                cacheFactory = cacheFactory,
                name = "DBTransactionRecovery_senderDistributionRecords",
                toPersistentEntityKey = { PersistentKey(it.sequenceNumber, it.timestamp) },
                fromPersistentEntity = { dbTxn ->
                    val key = TxRecoveryCacheValue.Key(dbTxn.compositeKey.timestamp)
                    key to TxRecoveryCacheValue(key, dbTxn.toSenderDistributionRecord())
                },
                toPersistentEntity = { key: TxRecoveryCacheValue.Key, value: TxRecoveryCacheValue ->
                    val senderDistributionRecord = value.metadata as SenderDistributionRecord
                    DBSenderDistributionRecord(key,
                            senderDistributionRecord.txId,
                            senderDistributionRecord.peerPartyId,
                            senderDistributionRecord.statesToRecord)
                },
                persistentEntityClass = DBSenderDistributionRecord::class.java
        )
    }

    private fun createReceiverDistributionRecoveryMap(cacheFactory: NamedCacheFactory)
            : AppendOnlyPersistentMapBase<TxRecoveryCacheValue.Key, TxRecoveryCacheValue, DBReceiverDistributionRecord, PersistentKey> {
        return AppendOnlyPersistentMap(
                cacheFactory = cacheFactory,
                name = "DBTransactionRecovery_receiverDistributionRecords",
                toPersistentEntityKey = { PersistentKey(it.sequenceNumber, it.timestamp) },
                fromPersistentEntity = { dbTxn ->
                    val key = TxRecoveryCacheValue.Key(dbTxn.compositeKey.timestamp)
                    key to TxRecoveryCacheValue(key, dbTxn.toReceiverDistributionRecord(cryptoService))
                },
                toPersistentEntity = { key: TxRecoveryCacheValue.Key, value: TxRecoveryCacheValue ->
                    val receiverDistributionRecord = value.metadata as ReceiverDistributionRecord
                    DBReceiverDistributionRecord(key,
                            receiverDistributionRecord.txId,
                            receiverDistributionRecord.initiatorPartyId,
                            receiverDistributionRecord.peerPartyIds,
                            receiverDistributionRecord.statesToRecord,
                            cryptoService)
                },
                persistentEntityClass = DBReceiverDistributionRecord::class.java
        )
    }

    override fun addUnnotarisedTransaction(transaction: SignedTransaction, metadata: TransactionMetadata, isInitiator: Boolean): Boolean {
        return addTransaction(transaction, TransactionStatus.IN_FLIGHT) {
            addTransactionRecoveryMetadata(transaction.id, metadata, isInitiator, clock) { false }
        }
    }

    override fun finalizeTransaction(transaction: SignedTransaction, metadata: TransactionMetadata, isInitiator: Boolean) =
            addTransaction(transaction) {
                addTransactionRecoveryMetadata(transaction.id, metadata, isInitiator, clock) { false }
            }

    override fun removeUnnotarisedTransaction(id: SecureHash): Boolean {
        return database.transaction {
            super.removeUnnotarisedTransaction(id)
            val criteriaBuilder = session.criteriaBuilder
            val deleteSenderDistributionRecords = criteriaBuilder.createCriteriaDelete(DBSenderDistributionRecord::class.java)
            val root = deleteSenderDistributionRecords.from(DBSenderDistributionRecord::class.java)
            deleteSenderDistributionRecords.where(criteriaBuilder.equal(root.get<String>(DBSenderDistributionRecord::txId.name), id.toString()))
            val deletedSenderDistributionRecords = if (session.createQuery(deleteSenderDistributionRecords).executeUpdate() != 0) {
                senderDistributionRecordStorage.locked {
                    senderDistributionRecordStorage.content.clear(id)
                    logger.debug { "Sender distribution record(s) have been removed for un-notarised transaction $id." }
                }
                true
            } else false
            val deleteReceiverDistributionRecords = criteriaBuilder.createCriteriaDelete(DBReceiverDistributionRecord::class.java)
            val rootReceiverDistributionRecord = deleteReceiverDistributionRecords.from(DBReceiverDistributionRecord::class.java)
            deleteReceiverDistributionRecords.where(criteriaBuilder.equal(rootReceiverDistributionRecord.get<String>(DBReceiverDistributionRecord::txId.name), id.toString()))
            val deletedReceiverDistributionRecords  = if (session.createQuery(deleteReceiverDistributionRecords).executeUpdate() != 0) {
                receiverDistributionRecordStorage.locked {
                    receiverDistributionRecordStorage.content.clear(id)
                    logger.debug { "Receiver distribution record has been removed for un-notarised transaction $id." }
                }
                true
            } else false
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

    private fun addTransactionRecoveryMetadata(txId: SecureHash, metadata: TransactionMetadata, isInitiator: Boolean, clock: CordaClock,
                                               updateFn: (TxRecoveryCacheValue.Key) -> Boolean): Boolean {
        database.transaction {
            if (isInitiator) {
                senderDistributionRecordStorage.locked {
                    metadata.peers?.map { peer ->
                        val peerPartyId = partyInfoCache.getPartyIdByCordaX500Name(peer)
                        val timestamp = clock.instant()
                        val distributionRecord = SenderDistributionRecord(txId,
                                peerPartyId,
                                metadata.statesToRecord ?: StatesToRecord.ONLY_RELEVANT,
                                timestamp)
                        val key = TxRecoveryCacheValue.Key(timestamp)
                        val cachedValue = TxRecoveryCacheValue(key, distributionRecord)
                        val addedOrUpdated = addOrUpdate(key, cachedValue) { k, _ -> updateFn(k) }
                        if (addedOrUpdated) {
                            logger.debug { "Sender distribution record for $txId has been recorded." }
                        } else {
                            logger.debug { "Sender distribution record for $txId is already recorded, so no need to re-record." }
                        }
                    }
                }
            } else {
                receiverDistributionRecordStorage.locked {
                    val timestamp = clock.instant()
                    val distributionRecord =
                            ReceiverDistributionRecord(txId,
                                    partyInfoCache.getPartyIdByCordaX500Name(metadata.initiator),
                                    metadata.peers?.map { partyInfoCache.getPartyIdByCordaX500Name(it) }?.toSet() ?: emptySet(),
                                    metadata.statesToRecord ?: StatesToRecord.ONLY_RELEVANT,
                                    timestamp)
                    val key = TxRecoveryCacheValue.Key(timestamp)
                    val cachedValue = TxRecoveryCacheValue(key, distributionRecord)
                    val addedOrUpdated = addOrUpdate(key, cachedValue) { k, _ -> updateFn(k) }
                    if (addedOrUpdated) {
                        logger.debug { "Receiver distribution record for $txId has been recorded." }
                    } else {
                        logger.debug { "Receiver distribution record for $txId is already recorded, so no need to re-record." }
                    }
                }
            }
        }
        return false
    }
}

// TO DO: https://r3-cev.atlassian.net/browse/ENT-9876
private fun CryptoService.decrypt(bytes: ByteArray): ByteArray {
    return bytes
}

// TO DO: https://r3-cev.atlassian.net/browse/ENT-9876
private fun CryptoService.encrypt(bytes: ByteArray): ByteArray {
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
        val peerPartyIds: Set<Long>,    // CordaX500Name hashCode()
        override val statesToRecord: StatesToRecord,
        override val timestamp: Instant
) : DistributionRecord(txId, statesToRecord, timestamp)

@CordaSerializable
enum class DistributionRecordType {
    SENDER, RECEIVER, ALL
}



