package com.krypt.app.data

import java.util.UUID

/**
 * Domain representation of an active unlock grant on the Subject device.
 * Inserted by [OutstandingRequestRepository.consumeAndInsertGrant] in the
 * same transaction as the request is consumed. Read by the Accessibility
 * Service (WP10) via LockerSessionStore (WP06) on every foreground event.
 */
data class UnlockGrant(
    /** Primary-key autoincrement; 0 means "not yet persisted". */
    val id: Long = 0L,
    /** Request that authorised this grant. */
    val requestId: UUID,
    val targetPackage: String,
    val grantedAtMs: Long,
    val expiresAtMs: Long,
)
