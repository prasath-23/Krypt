package com.krypt.app.deeplink

import java.util.UUID

/**
 * A single outstanding unlock request, as emitted by the Subject device and
 * consumed (for shape only; no cryptographic validation) by the Guardian
 * device. The cryptographic gate on the Guardian side is the PIN entry UI
 * (WP14); this DTO merely carries the metadata needed to produce the matching
 * approval URL.
 *
 * See polaris-specs/001-krypt-app-locker/contracts/request.md for field
 * semantics and size budgets.
 */
data class UnlockRequest(
    val requestId: UUID,
    val targetPackage: String,
    /** Per-request nonce; exactly [SALT_BYTES] long. */
    val salt: ByteArray,
    /** Wall-clock seconds (Subject) at issue time. */
    val issuedAt: Long,
    /** Request validity window in seconds. */
    val ttlSeconds: Long,
) {

    /** Convenience: absolute expiry in seconds since Unix epoch. */
    val expiresAtSeconds: Long get() = issuedAt + ttlSeconds

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnlockRequest) return false
        return requestId == other.requestId &&
            targetPackage == other.targetPackage &&
            salt.contentEquals(other.salt) &&
            issuedAt == other.issuedAt &&
            ttlSeconds == other.ttlSeconds
    }

    override fun hashCode(): Int {
        var h = requestId.hashCode()
        h = 31 * h + targetPackage.hashCode()
        h = 31 * h + salt.contentHashCode()
        h = 31 * h + issuedAt.hashCode()
        h = 31 * h + ttlSeconds.hashCode()
        return h
    }

    companion object {
        /** Per-request salt length mandated by contracts/request.md. */
        const val SALT_BYTES = 16

        /** Default TTL: 30 minutes. */
        const val DEFAULT_TTL_SECONDS = 1800L
    }
}

/**
 * All observable error states of [UnlockRequestParser.parse].
 *
 * Each variant corresponds to one row of the error table in
 * contracts/request.md.
 */
sealed interface RequestParseError {
    data object BadScheme : RequestParseError
    data object WrongVersion : RequestParseError
    data class MissingParam(val name: String) : RequestParseError
    data object BadBase64 : RequestParseError
    data object BadUuid : RequestParseError
    data object BadPackageName : RequestParseError
    data object BadSaltLength : RequestParseError
    data object BadTimestamp : RequestParseError
    data object Expired : RequestParseError
}
