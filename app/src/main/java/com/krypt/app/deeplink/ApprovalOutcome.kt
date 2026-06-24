package com.krypt.app.deeplink

import java.util.UUID

/**
 * Successful result of [ApprovalConsumer.consume]: the approval matched an
 * outstanding request, decrypted, and produced a persisted unlock grant.
 *
 * The Subject-side UI uses [targetPackage] to relaunch the now-unlocked app
 * and [grantExpiresAtMs] to display the expiry time in the success toast.
 */
data class ApprovalOutcome(
    val requestId: UUID,
    val targetPackage: String,
    val grantedAtMs: Long,
    val grantExpiresAtMs: Long,
    /** Row id assigned by the UnlockGrant table; useful for logs and tests. */
    val grantId: Long,
)
