package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shape-only parser for Amendment 1 `krypt://approve?...` URLs.
 *
 * Returns an [ApprovalEnvelope] carrying the split blob layout
 * `nonceForHkdf(16) || aesNonce(12) || ciphertext || tag(16)` plus the
 * `req` / `iat` metadata needed to look up the matching OutstandingRequest.
 * Decryption is NOT performed here - it happens in [ApprovalConsumer],
 * which has access to [com.krypt.app.security.MasterKeyStore].
 */
@Singleton
class ApprovalLinkParser @Inject constructor() {

    fun parse(url: String): Outcome<ApprovalEnvelope, ApprovalError> {
        val parsed = UrlCodec.parse(url)
            ?: return Outcome.err(ApprovalError.BadScheme)

        if (parsed.authority != DeepLinkScheme.AUTHORITY_APPROVE) {
            return Outcome.err(ApprovalError.BadScheme)
        }

        val version = parsed.params[Params.VERSION]
            ?: return Outcome.err(ApprovalError.MissingParam(Params.VERSION))
        if (version != DeepLinkScheme.PROTOCOL_VERSION) {
            return Outcome.err(ApprovalError.WrongVersion)
        }

        val reqIdString = parsed.params[Params.REQUEST_ID]
            ?: return Outcome.err(ApprovalError.MissingParam(Params.REQUEST_ID))
        val requestId = try {
            UUID.fromString(reqIdString)
        } catch (_: IllegalArgumentException) {
            return Outcome.err(ApprovalError.BadUuid)
        }
        if (requestId.version() != 4) {
            return Outcome.err(ApprovalError.BadUuid)
        }

        val dataB64 = parsed.params[Params.DATA]
            ?: return Outcome.err(ApprovalError.MissingParam(Params.DATA))
        val data = Base64Url.tryDecode(dataB64)
            ?: return Outcome.err(ApprovalError.BadBase64)

        // Amendment 1 minimum size: nonceForHkdf(16) + aesNonce(12) + tag(16) = 44.
        val minSize = ApprovalLinkBuilder.NONCE_FOR_HKDF_BYTES +
            AesGcmCipher.NONCE_BYTES +
            MIN_CIPHERTEXT_AND_TAG_BYTES
        if (data.size < minSize) {
            return Outcome.err(ApprovalError.BadBase64)
        }

        val iatString = parsed.params[Params.ISSUED_AT]
            ?: return Outcome.err(ApprovalError.MissingParam(Params.ISSUED_AT))
        val iat = iatString.toLongOrNull()
        if (iat == null || iat <= 0) {
            return Outcome.err(ApprovalError.BadBase64)
        }

        val hkdfOffset = 0
        val aesOffset = ApprovalLinkBuilder.NONCE_FOR_HKDF_BYTES
        val ctOffset = aesOffset + AesGcmCipher.NONCE_BYTES

        return Outcome.ok(
            ApprovalEnvelope(
                requestId = requestId,
                nonceForHkdf = data.copyOfRange(hkdfOffset, aesOffset),
                aesNonce = data.copyOfRange(aesOffset, ctOffset),
                ciphertextAndTag = data.copyOfRange(ctOffset, data.size),
                issuedAt = iat,
            )
        )
    }

    companion object {
        /** 16-byte GCM tag; empty ciphertext is allowed at this layer. */
        const val MIN_CIPHERTEXT_AND_TAG_BYTES = 16
    }
}

/**
 * Shape-parsed approval URL (Amendment 1 layout). Decryption happens in
 * [ApprovalConsumer].
 */
data class ApprovalEnvelope(
    val requestId: UUID,
    /** Amendment 1: 16-byte HKDF info material (formerly unused). */
    val nonceForHkdf: ByteArray,
    /** 12-byte AES-GCM nonce. */
    val aesNonce: ByteArray,
    /** ciphertext || 16-byte tag. */
    val ciphertextAndTag: ByteArray,
    /** Guardian-clock seconds when the approval was issued. */
    val issuedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApprovalEnvelope) return false
        return requestId == other.requestId &&
            nonceForHkdf.contentEquals(other.nonceForHkdf) &&
            aesNonce.contentEquals(other.aesNonce) &&
            ciphertextAndTag.contentEquals(other.ciphertextAndTag) &&
            issuedAt == other.issuedAt
    }

    override fun hashCode(): Int {
        var h = requestId.hashCode()
        h = 31 * h + nonceForHkdf.contentHashCode()
        h = 31 * h + aesNonce.contentHashCode()
        h = 31 * h + ciphertextAndTag.contentHashCode()
        h = 31 * h + issuedAt.hashCode()
        return h
    }
}
