package net.corda.client.mock

import net.corda.core.contracts.Amount
import net.corda.core.contracts.GBP
import net.corda.core.contracts.USD
import net.corda.core.identity.Party
import net.corda.core.serialization.OpaqueBytes
import net.corda.flows.CashFlowCommand
import java.util.*

/**
 * [Generator]s for incoming/outgoing cash flow events between parties. It doesn't necessarily generate correct events!
 * Especially at the beginning of simulation there might be few insufficient spend errors.
 */

open class EventGenerator(val parties: List<Party>, val currencies: List<Currency>, val notary: Party) {
    protected val partyGenerator = Generator.pickOne(parties)
    protected val issueRefGenerator = Generator.intRange(0, 1).map { number -> OpaqueBytes(ByteArray(1, { number.toByte() })) }
    protected val amountGenerator = Generator.longRange(10000, 1000000)
    protected val currencyGenerator = Generator.pickOne(currencies)
    protected val currencyMap: MutableMap<Currency, Long> = mutableMapOf(USD to 0L, GBP to 0L) // Used for estimation of how much money we have in general.

    protected fun addToMap(ccy: Currency, amount: Long) {
        currencyMap.computeIfPresent(ccy) { _, value -> Math.max(0L, value + amount) }
    }

    protected val issueCashGenerator = amountGenerator.combine(partyGenerator, issueRefGenerator, currencyGenerator) { amount, to, issueRef, ccy ->
        addToMap(ccy, amount)
        CashFlowCommand.IssueCash(Amount(amount, ccy), issueRef, to, notary)
    }

    protected val exitCashGenerator = amountGenerator.combine(issueRefGenerator, currencyGenerator) { amount, issueRef, ccy ->
        addToMap(ccy, -amount)
        CashFlowCommand.ExitCash(Amount(amount, ccy), issueRef)
    }

    open val moveCashGenerator = amountGenerator.combine(partyGenerator, currencyGenerator) { amountIssued, recipient, currency ->
        CashFlowCommand.PayCash(Amount(amountIssued, currency), recipient)
    }

    open val issuerGenerator = Generator.frequency(listOf(
            0.1 to exitCashGenerator,
            0.9 to issueCashGenerator
    ))
}
