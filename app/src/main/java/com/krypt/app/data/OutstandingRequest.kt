package com.krypt.app.data

import java.util.UUID

/**
 * Domain representation of a pending unlock request on the Subject device
 * (or the Guardian-side copy, after parse). Persisted via
 * [OutstandingRequestRepository] (implementations land in WP06).
 *
 * Invariant: [consumed] flips false->true exactly once, atomically, through
 * [OutstandingRequestRepository.consumeAndInsertGrant].
 */
data class OutstandingRequest(
    val requestId: UUID,
    val targetPackage: String,
    /** Per-request salt. Exactly 16 bytes. */
    val salt: ByteArray,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val consumed: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutstandingRequest) return false
        return requestId == other.requestId &&
            targetPackage == other.targetPackage &&
            salt.contentEquals(other.salt) &&
            issuedAtMs == other.issuedAtMs &&
            expiresAtMs == other.expiresAtMs &&
            consumed == other.consumed
    }

    override fun hashCode(): Int {
        var h = requestId.hashCode()
        h = 31 * h + targetPackage.hashCode()
        h = 31 * h + salt.contentHashCode()
        h = 31 * h + issuedAtMs.hashCode()
        h = 31 * h + expiresAtMs.hashCode()
        h = 31 * h + consumed.hashCode()
        return h
    }
}
