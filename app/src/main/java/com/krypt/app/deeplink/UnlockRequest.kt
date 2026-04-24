package com.krypt.app.deeplink

import java.util.UUID

/**
 * A single outstanding unlock request, as emitted by the Subject device and
 * consumed (for shape only; no cryptographic validation) by the Guardian
 * device.
 *
 * Amendment 1 shape: the request URL now carries the Subject's *setup* salt
 * (the 16 bytes the PIN was PBKDF2'd against at Guardian-on-Subject PIN
 * setup, not a per-request random value) alongside a 32-byte `pinProof`
 * (`HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")`) so the Guardian can
 * locally verify a typed PIN before composing an approval. The per-request
 * randomness that binds a specific approval to its request now lives inside
 * the approval's data blob (`nonceForHkdf`), not in this URL.
 *
 * See polaris-specs/001-krypt-app-locker/contracts/request.md for field
 * semantics and size budgets.
 */
data class UnlockRequest(
    val requestId: UUID,
    val targetPackage: String,
    /** Setup-time salt used for PBKDF2(PIN, salt). Exactly [SALT_BYTES]. */
    val salt: ByteArray,
    /** HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof"). Exactly [PIN_PROOF_BYTES]. */
    val pinProof: ByteArray,
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
            pinProof.contentEquals(other.pinProof) &&
            issuedAt == other.issuedAt &&
            ttlSeconds == other.ttlSeconds
    }

    override fun hashCode(): Int {
        var h = requestId.hashCode()
        h = 31 * h + targetPackage.hashCode()
        h = 31 * h + salt.contentHashCode()
        h = 31 * h + pinProof.contentHashCode()
        h = 31 * h + issuedAt.hashCode()
        h = 31 * h + ttlSeconds.hashCode()
        return h
    }

    companion object {
        /** Salt length in bytes (Amendment 1 = setup salt). */
        const val SALT_BYTES = 16

        /** HMAC-SHA-256 tag length — `pinProof`. */
        const val PIN_PROOF_BYTES = 32

        /**
         * Default TTL: 5 minutes (Amendment 1 FR-019). Previous shipped
         * default was 1800 s; tightened to limit replay window on captured
         * URLs.
         */
        const val DEFAULT_TTL_SECONDS = 300L
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
    data object BadPinProofLength : RequestParseError
    data object BadTimestamp : RequestParseError
    data object Expired : RequestParseError
}
