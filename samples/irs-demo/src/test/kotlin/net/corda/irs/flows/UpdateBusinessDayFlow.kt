package net.corda.irs.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.node.utilities.TestClock
import java.time.LocalDate

/**
 * This is a less temporary, demo-oriented way of initiating processing of temporal events.
 */
object UpdateBusinessDayFlow {

    @CordaSerializable
    data class UpdateBusinessDayMessage(val date: LocalDate)

    @InitiatedBy(Broadcast::class)
    private class UpdateBusinessDayHandler(val otherParty: Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val message = receive<UpdateBusinessDayMessage>(otherParty).unwrap { it }
            (serviceHub.clock as TestClock).updateDate(message.date)
        }
    }

    @InitiatingFlow
    @StartableByRPC
    class Broadcast(val date: LocalDate, override val progressTracker: ProgressTracker) : FlowLogic<Unit>() {
        constructor(date: LocalDate) : this(date, tracker())

        companion object {
            object NOTIFYING : ProgressTracker.Step("Notifying peers")

            fun tracker() = ProgressTracker(NOTIFYING)
        }

        @Suspendable
        override fun call(): Unit {
            progressTracker.currentStep = NOTIFYING
            for (recipient in getRecipients()) {
                doNextRecipient(recipient)
            }
        }

        /**
         * Returns recipients ordered by legal name, with notary nodes taking priority over party nodes.
         * Ordering is required so that we avoid situations where on clock update a party starts a scheduled flow, but
         * the notary or counterparty still use the old clock, so the time-window on the transaction does not validate.
         */
        private fun getRecipients(): Iterable<Party> {
            val notaryParties = serviceHub.networkMapCache.notaryIdentities.map { it.party }
            // TODO Rewrite that
            val peerParties = serviceHub.networkMapCache.partyNodes.filter { it.legalIdentities.all { it !in notaryParties } }.map { it.legalIdentities.first() }.sortedBy { it.name.toString() }
            return notaryParties + peerParties
        }

        @Suspendable
        private fun doNextRecipient(recipient: Party) {
            send(recipient, UpdateBusinessDayMessage(date))
        }
    }
}