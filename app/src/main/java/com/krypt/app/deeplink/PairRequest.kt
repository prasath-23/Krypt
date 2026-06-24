package com.krypt.app.deeplink

import java.util.UUID

/**
 * Subject-side pair-init message (krypt://pair).
 *
 * See polaris-specs/001-krypt-app-locker/contracts/pair.md.
 */
data class PairRequest(
    val subjectId: UUID,
    val subjectDisplayName: String,
    /** 32-byte X25519 public key (little-endian u-coordinate). */
    val subjectEphPub: ByteArray,
    /** Issue time in seconds since Unix epoch. */
    val issuedAt: Long,
    /** TTL in seconds; default 15 minutes. */
    val ttlSeconds: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairRequest) return false
        return subjectId == other.subjectId &&
            subjectDisplayName == other.subjectDisplayName &&
            subjectEphPub.contentEquals(other.subjectEphPub) &&
            issuedAt == other.issuedAt &&
            ttlSeconds == other.ttlSeconds
    }

    override fun hashCode(): Int {
        var h = subjectId.hashCode()
        h = 31 * h + subjectDisplayName.hashCode()
        h = 31 * h + subjectEphPub.contentHashCode()
        h = 31 * h + issuedAt.hashCode()
        h = 31 * h + ttlSeconds.hashCode()
        return h
    }

    companion object {
        const val DEFAULT_TTL_SECONDS = 900L
    }
}

/**
 * Observable errors from [PairRequestParser.parse].
 */
sealed interface PairParseError {
    data object BadScheme : PairParseError
    data object WrongVersion : PairParseError
    data class MissingParam(val name: String) : PairParseError
    data object BadBase64 : PairParseError
    data object BadUuid : PairParseError
    data object BadKeyLength : PairParseError
    data object BadTimestamp : PairParseError
    data object Expired : PairParseError
}
