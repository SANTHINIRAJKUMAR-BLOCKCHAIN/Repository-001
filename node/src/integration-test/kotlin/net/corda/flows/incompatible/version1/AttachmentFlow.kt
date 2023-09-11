package net.corda.flows.incompatible.version1

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.incompatible.version1.ATTACHMENT_PROGRAM_ID
import net.corda.contracts.incompatible.version1.AttachmentContract
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
@InitiatingFlow
@StartableByRPC
class AttachmentFlow(private val otherSide: Party,
                     private val notary: Party,
                     private val attachId: SecureHash.SHA256) : FlowLogic<SignedTransaction>() {

    object SIGNING : ProgressTracker.Step("Signing transaction")

    override val progressTracker: ProgressTracker = ProgressTracker(SIGNING)

    @Suspendable
    override fun call(): SignedTransaction {
        // Create a trivial transaction with an output that describes the attachment, and the attachment itself
        val ptx = TransactionBuilder(notary)
                .addOutputState(AttachmentContract.State(attachId), ATTACHMENT_PROGRAM_ID)
                .addCommand(AttachmentContract.Command, ourIdentity.owningKey)
                .addAttachment(attachId)

        progressTracker.currentStep = SIGNING

        val stx = serviceHub.signInitialTransaction(ptx)

        // Send the transaction to the other recipient
        return subFlow(FinalityFlow(stx, initiateFlow(otherSide)))
    }
}

@InitiatedBy(AttachmentFlow::class)
class StoreAttachmentFlow(private val otherSide: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        // As a non-participant to the transaction we need to record all states
        subFlow(ReceiveFinalityFlow(otherSide, statesToRecord = StatesToRecord.ALL_VISIBLE))
    }
}

