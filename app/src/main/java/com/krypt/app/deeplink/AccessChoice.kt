package com.krypt.app.deeplink

/**
 * What the Guardian allows when approving a request. It travels inside the
 * encrypted approval payload ([ApprovalPayload]), so the child's phone
 * learns it only from an approval made with the Guardian PIN.
 */
sealed interface AccessChoice {

    /** Unlock the app once, for [minutes]. */
    data class OneTime(val minutes: Int) : AccessChoice {
        init {
            require(minutes in 1..MAX_MINUTES) { "minutes ($minutes) must be in 1..$MAX_MINUTES" }
        }
    }

    /**
     * Up to [minutes] in each [periodMinutes]-minute period, every day for
     * [days] calendar days starting today. Periods follow the clock from
     * midnight, like the day (every 90 minutes: 00:00-01:30, 01:30-03:00, ...);
     * when the period doesn't divide a day, the day's last period is shorter
     * and ends at midnight. A period of [DAY_MINUTES] is the calendar day:
     * "[minutes] a day".
     */
    data class EveryDay(val minutes: Int, val days: Int, val periodMinutes: Int = DAY_MINUTES) : AccessChoice {
        init {
            require(periodMinutes in MIN_PERIOD_MINUTES..DAY_MINUTES) {
                "periodMinutes ($periodMinutes) must be in $MIN_PERIOD_MINUTES..$DAY_MINUTES"
            }
            require(minutes in 1..periodMinutes) { "minutes ($minutes) must be in 1..$periodMinutes" }
            require(days in 1..MAX_DAYS) { "days ($days) must be in 1..$MAX_DAYS" }
        }
    }

    /**
     * Unlock the app for good: its lock is removed, as Home's "Remove lock"
     * does on the child's phone. It stays open until someone locks it again.
     */
    data object ForGood : AccessChoice

    companion object {
        const val DEFAULT_MINUTES = 15
        const val DEFAULT_DAYS = 7

        /** Bounds an every-day rule on both phones; the Guardian can end one early. */
        const val MAX_DAYS = 365

        /** A day. Bounds the approval on both phones. */
        const val MAX_MINUTES = 24 * 60

        /** An every-day rule's period when it is the whole day. */
        const val DAY_MINUTES = MAX_MINUTES

        /** The shortest period repeating time can come back in. */
        const val MIN_PERIOD_MINUTES = 5
    }
}
