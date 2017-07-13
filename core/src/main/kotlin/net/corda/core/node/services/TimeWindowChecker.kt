package net.corda.core.node.services

import net.corda.core.contracts.TimeWindow
import net.corda.core.utilities.seconds
import net.corda.core.until
import java.time.Clock

/**
 * Checks if the current instant provided by the input clock falls within the provided time-window.
 */
class TimeWindowChecker(val clock: Clock = Clock.systemUTC()) {
    fun isValid(timeWindow: TimeWindow): Boolean {
        val fromTime = timeWindow.fromTime
        val untilTime = timeWindow.untilTime

        val now = clock.instant()

        // We don't need to test for (fromTime == null && untilTime == null) or backwards bounds because the TimeWindow
        // constructor already checks that.
        if (fromTime != null && now < fromTime) return false
        if (untilTime != null && now > untilTime) return false
        return true
    }
}
