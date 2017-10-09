package net.corda.node.services.api

import net.corda.core.node.services.VaultService
import net.corda.core.transactions.CoreTransaction
import net.corda.core.transactions.NotaryChangeWireTransaction
import net.corda.core.transactions.WireTransaction

interface VaultServiceInternal : VaultService {
    /**
     * Splits the provided [txns] into batches of [WireTransaction] and [NotaryChangeWireTransaction].
     * This is required because the batches get aggregated into single updates, and we want to be able to
     * indicate whether an update consists entirely of regular or notary change transactions, which may require
     * different processing logic.
     */
    fun notifyAll(txns: Iterable<CoreTransaction>)

    /** Same as notifyAll but with a single transaction. */
    fun notify(tx: CoreTransaction) = notifyAll(listOf(tx))
}
