package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.DoNotImplement
import net.corda.core.contracts.ComponentGroupEnum
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TimeWindow
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.TransactionSignature
import net.corda.core.identity.Party
import net.corda.core.internal.BackpressureAwareTimedFlow
import net.corda.core.internal.FetchDataFlow
import net.corda.core.internal.notary.generateSignature
import net.corda.core.internal.notary.validateSignatures
import net.corda.core.internal.pushToLoggingContext
import net.corda.core.transactions.*
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.UntrustworthyData
import net.corda.core.utilities.unwrap
import java.util.function.Predicate

class NotaryFlow {
    /**
     * A flow to be used by a party for obtaining signature(s) from a [NotaryService] ascertaining the transaction
     * time-window is correct and none of its inputs have been used in another completed transaction.
     *
     * In case of a single-node or Raft notary, the flow will return a single signature. For the BFT notary multiple
     * signatures will be returned – one from each replica that accepted the input state commit.
     *
     * @throws NotaryException in case the any of the inputs to the transaction have been consumed
     *                         by another transaction or the time-window is invalid or
     *                         the parameters used for this transaction are no longer in force in the network.
     */
    @DoNotImplement
    @InitiatingFlow
    open class Client(
            private val stx: SignedTransaction,
            override val progressTracker: ProgressTracker
    ) : BackpressureAwareTimedFlow<List<TransactionSignature>>() {
        constructor(stx: SignedTransaction) : this(stx, tracker())

        companion object {
            object REQUESTING : ProgressTracker.Step("Requesting signature by Notary service")
            object VALIDATING : ProgressTracker.Step("Validating response from Notary service")

            fun tracker() = ProgressTracker(REQUESTING, VALIDATING)
        }

        @Suspendable
        @Throws(NotaryException::class)
        override fun call(): List<TransactionSignature> {
            stx.pushToLoggingContext()
            val notaryParty = checkTransaction()
            progressTracker.currentStep = REQUESTING
            logger.info("Sending transaction to notary: ${notaryParty.name}.")
            val response = notarise(notaryParty)
            logger.info("Notary responded.")
            progressTracker.currentStep = VALIDATING
            return validateResponse(response, notaryParty)
        }

        /**
         * Checks that the transaction specifies a valid notary, and verifies that it contains all required signatures
         * apart from the notary's.
         */
        protected fun checkTransaction(): Party {
            val notaryParty = stx.notary ?: throw IllegalStateException("Transaction does not specify a Notary")
            check(serviceHub.networkMapCache.isNotary(notaryParty)) { "$notaryParty is not a notary on the network" }
            check(serviceHub.loadStates(stx.inputs.toSet() + stx.references.toSet()).all { it.state.notary == notaryParty }) {
                "Input states and reference input states must have the same Notary"
            }
            stx.resolveTransactionWithSignatures(serviceHub).verifySignaturesExcept(notaryParty.owningKey)
            return notaryParty
        }

        /** Notarises the transaction with the [notaryParty], obtains the notary's signature(s). */
        @Throws(NotaryException::class)
        @Suspendable
        protected fun notarise(notaryParty: Party): UntrustworthyData<NotarisationResponse> {
            val session = initiateFlow(notaryParty)
            val requestSignature = generateRequestSignature()
            return if (serviceHub.networkMapCache.isValidatingNotary(notaryParty)) {
                sendAndReceiveValidating(session, requestSignature)
            } else {
                sendAndReceiveNonValidating(notaryParty, session, requestSignature)
            }
        }

        @Suspendable
        private fun sendAndReceiveValidating(session: FlowSession, signature: NotarisationRequestSignature): UntrustworthyData<NotarisationResponse> {
            val payload = NotarisationPayload(stx, signature)
            subFlow(NotarySendTransactionFlow(session, payload))
            return receiveResultOrTiming(session)
        }

        @Suspendable
        private fun sendAndReceiveNonValidating(notaryParty: Party, session: FlowSession, signature: NotarisationRequestSignature): UntrustworthyData<NotarisationResponse> {
            val ctx = stx.coreTransaction
            val tx = when (ctx) {
                is ContractUpgradeWireTransaction -> ctx.buildFilteredTransaction()
                is WireTransaction -> ctx.buildFilteredTransaction(Predicate {
                    it is StateRef || it is ReferenceStateRef || it is TimeWindow || it == notaryParty || it is NetworkParametersHash
                })
                else -> ctx
            }
            session.send(NotarisationPayload(tx, signature))
            return receiveResultOrTiming(session)
        }

        /** Checks that the notary's signature(s) is/are valid. */
        protected fun validateResponse(response: UntrustworthyData<NotarisationResponse>, notaryParty: Party): List<TransactionSignature> {
            return response.unwrap {
                it.validateSignatures(stx.id, notaryParty)
                it.signatures
            }
        }

        /**
         * The [NotarySendTransactionFlow] flow is similar to [SendTransactionFlow], but uses [NotarisationPayload] as the
         * initial message, and retries message delivery.
         */
        private class NotarySendTransactionFlow(otherSide: FlowSession, payload: NotarisationPayload) : DataVendingFlow(otherSide, payload) {
            @Suspendable
            override fun sendPayloadAndReceiveDataRequest(otherSideSession: FlowSession, payload: Any): UntrustworthyData<FetchDataFlow.Request> {
                return otherSideSession.sendAndReceiveWithRetry(payload)
            }
        }

        /**
         * Ensure that transaction ID instances are not referenced in the serialized form in case several input states are outputs of the
         * same transaction.
         */
        private fun generateRequestSignature(): NotarisationRequestSignature {
            // TODO: This is not required any more once our AMQP serialization supports turning off object referencing.
            val notarisationRequest = NotarisationRequest(stx.inputs.map { it.copy(txhash = SecureHash.parse(it.txhash.toString())) }, stx.id)
            return notarisationRequest.generateSignature(serviceHub)
        }
    }
}
