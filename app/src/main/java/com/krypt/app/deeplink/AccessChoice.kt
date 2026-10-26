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

    /** Up to [minutes] a day, every day for [days] calendar days starting today. */
    data class EveryDay(val minutes: Int, val days: Int) : AccessChoice {
        init {
            require(minutes in 1..MAX_MINUTES) { "minutes ($minutes) must be in 1..$MAX_MINUTES" }
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
    }
}
