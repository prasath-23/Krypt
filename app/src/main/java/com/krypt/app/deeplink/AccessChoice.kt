package com.krypt.app.deeplink

/**
 * What the Guardian allows when approving a request. It travels inside the
 * encrypted approval payload ([ApprovalPayload]), so the child's phone
 * learns it only from an approval made with the Guardian PIN.
 */
sealed interface AccessChoice {
    /** Minutes of access. */
    val minutes: Int

    /** Unlock the app once, for [minutes]. */
    data class OneTime(override val minutes: Int) : AccessChoice {
        init {
            require(minutes in 1..MAX_MINUTES) { "minutes ($minutes) must be in 1..$MAX_MINUTES" }
        }
    }

    companion object {
        const val DEFAULT_MINUTES = 15

        /** A day. Bounds the approval on both phones. */
        const val MAX_MINUTES = 24 * 60
    }
}
