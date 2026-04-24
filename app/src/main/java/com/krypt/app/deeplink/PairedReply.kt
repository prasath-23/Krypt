package com.krypt.app.deeplink

import java.util.UUID

/**
 * Guardian-side pair-completion message (krypt://paired). Authenticated
 * with HMAC-SHA-256 under K_pair; see contracts/paired.md for the
 * canonical MAC-input layout.
 */
data class PairedReply(
    val subjectId: UUID,
    val guardianDisplayName: String,
    /** 32-byte Guardian PIN-KDF salt; rotated on PIN change. */
    val guardianPubSalt: ByteArray,
    /** 32-byte Guardian X25519 ephemeral public key. */
    val guardianEphPub: ByteArray,
    val kdfIterations: Int,
    val issuedAt: Long,
    val ttlSeconds: Long,
    /** 32-byte HMAC-SHA-256 tag. */
    val mac: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairedReply) return false
        return subjectId == other.subjectId &&
            guardianDisplayName == other.guardianDisplayName &&
            guardianPubSalt.contentEquals(other.guardianPubSalt) &&
            guardianEphPub.contentEquals(other.guardianEphPub) &&
            kdfIterations == other.kdfIterations &&
            issuedAt == other.issuedAt &&
            ttlSeconds == other.ttlSeconds &&
            mac.contentEquals(other.mac)
    }

    override fun hashCode(): Int {
        var h = subjectId.hashCode()
        h = 31 * h + guardianDisplayName.hashCode()
        h = 31 * h + guardianPubSalt.contentHashCode()
        h = 31 * h + guardianEphPub.contentHashCode()
        h = 31 * h + kdfIterations.hashCode()
        h = 31 * h + issuedAt.hashCode()
        h = 31 * h + ttlSeconds.hashCode()
        h = 31 * h + mac.contentHashCode()
        return h
    }

    companion object {
        const val DEFAULT_TTL_SECONDS = 900L
    }
}

/**
 * Observable errors from [PairedReplyVerifier.parseAndVerify].
 */
sealed interface PairedParseError {
    data object BadScheme : PairedParseError
    data object WrongVersion : PairedParseError
    data class MissingParam(val name: String) : PairedParseError
    data object BadBase64 : PairedParseError
    data object BadUuid : PairedParseError
    data object BadKeyLength : PairedParseError
    data object BadSaltLength : PairedParseError
    data object BadIterations : PairedParseError
    data object BadTimestamp : PairedParseError
    data object WrongSubjectId : PairedParseError
    data object Expired : PairedParseError
    data object BadMac : PairedParseError
}
