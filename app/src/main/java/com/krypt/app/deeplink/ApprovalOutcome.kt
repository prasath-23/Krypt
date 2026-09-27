package com.krypt.app.deeplink

import com.krypt.app.data.daily.DailyAllowance
import java.util.UUID

/**
 * Successful result of [ApprovalConsumer.consume]: the approval matched an
 * outstanding request, decrypted, and what it allows was saved.
 *
 * The Subject-side UI uses [targetPackage] to relaunch the now-unlocked app.
 */
sealed interface ApprovalOutcome {
    val requestId: UUID
    val targetPackage: String

    /** A one-time unlock until [grantExpiresAtMs]. */
    data class OneTime(
        override val requestId: UUID,
        override val targetPackage: String,
        val grantedAtMs: Long,
        val grantExpiresAtMs: Long,
        /** Row id assigned by the UnlockGrant table; useful for logs and tests. */
        val grantId: Long,
    ) : ApprovalOutcome

    /** An every-day rule, now saved. */
    data class EveryDay(
        override val requestId: UUID,
        override val targetPackage: String,
        val allowance: DailyAllowance,
    ) : ApprovalOutcome
}
