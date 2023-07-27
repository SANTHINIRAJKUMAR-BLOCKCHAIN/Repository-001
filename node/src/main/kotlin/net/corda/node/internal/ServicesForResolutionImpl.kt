package net.corda.node.internal

import net.corda.core.contracts.Attachment
import net.corda.core.contracts.AttachmentResolutionException
import net.corda.core.contracts.ContractAttachment
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.contracts.TransactionState
import net.corda.core.cordapp.CordappProvider
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.SerializedStateAndRef
import net.corda.core.internal.uncheckedCast
import net.corda.core.node.NetworkParameters
import net.corda.core.node.services.AttachmentStorage
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.NetworkParametersService
import net.corda.core.node.services.TransactionStorage
import net.corda.core.transactions.BaseTransaction
import net.corda.core.transactions.ContractUpgradeWireTransaction
import net.corda.core.transactions.NotaryChangeWireTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.transactions.WireTransaction.Companion.resolveStateRefBinaryComponent

data class ServicesForResolutionImpl(
        override val identityService: IdentityService,
        override val attachments: AttachmentStorage,
        override val cordappProvider: CordappProvider,
        override val networkParametersService: NetworkParametersService,
        private val validatedTransactions: TransactionStorage
) : NodeServicesForResolution {
    override val networkParameters: NetworkParameters get() = networkParametersService.lookup(networkParametersService.currentHash) ?:
            throw IllegalArgumentException("No current parameters in network parameters storage")

    @Throws(TransactionResolutionException::class)
    override fun loadState(stateRef: StateRef): TransactionState<*> {
        return toBaseTransaction(stateRef.txhash).outputs[stateRef.index]
    }

    override fun <T : ContractState, C : MutableCollection<StateAndRef<T>>> loadStates(input: Iterable<StateRef>, output: C): C {
        val baseTxs = HashMap<SecureHash, BaseTransaction>()
        return input.mapTo(output) { stateRef ->
            val baseTx = baseTxs.computeIfAbsent(stateRef.txhash, ::toBaseTransaction)
            StateAndRef(uncheckedCast(baseTx.outputs[stateRef.index]), stateRef)
        }
    }

    @Throws(TransactionResolutionException::class, AttachmentResolutionException::class)
    override fun loadContractAttachment(stateRef: StateRef): Attachment {
        // We may need to recursively chase transactions if there are notary changes.
        fun inner(stateRef: StateRef, forContractClassName: String?): Attachment {
            val ctx = getSignedTransaction(stateRef.txhash).coreTransaction
            when (ctx) {
                is WireTransaction -> {
                    val transactionState = ctx.outRef<ContractState>(stateRef.index).state
                    for (attachmentId in ctx.attachments) {
                        val attachment = attachments.openAttachment(attachmentId)
                        if (attachment is ContractAttachment && (forContractClassName ?: transactionState.contract) in attachment.allContracts) {
                            return attachment
                        }
                    }
                    throw AttachmentResolutionException(stateRef.txhash)
                }
                is ContractUpgradeWireTransaction -> {
                    return attachments.openAttachment(ctx.upgradedContractAttachmentId) ?: throw AttachmentResolutionException(stateRef.txhash)
                }
                is NotaryChangeWireTransaction -> {
                    val transactionState = SerializedStateAndRef(resolveStateRefBinaryComponent(stateRef, this)!!, stateRef).toStateAndRef().state
                    // TODO: check only one (or until one is resolved successfully), max recursive invocations check?
                    return ctx.inputs.map { inner(it, transactionState.contract) }.firstOrNull() ?: throw AttachmentResolutionException(stateRef.txhash)
                }
                else -> throw UnsupportedOperationException("Attempting to resolve attachment for index ${stateRef.index} of a ${ctx.javaClass} transaction. This is not supported.")
            }
        }
        return inner(stateRef, null)
    }

    private fun toBaseTransaction(txhash: SecureHash): BaseTransaction = getSignedTransaction(txhash).resolveBaseTransaction(this)

    private fun getSignedTransaction(txhash: SecureHash): SignedTransaction {
        return validatedTransactions.getTransaction(txhash) ?: throw TransactionResolutionException(txhash)
    }
}
