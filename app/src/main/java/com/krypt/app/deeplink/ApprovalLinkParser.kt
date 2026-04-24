package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shape-only parser for `krypt://approve?...` URLs.
 *
 * Returns an [ApprovalEnvelope] carrying the raw encrypted `data` blob
 * (nonce || ciphertext || tag) plus the `req` / `iat` metadata needed to
 * look up the matching OutstandingRequest. Decryption is NOT performed here
 * — it happens in [ApprovalConsumer], which has access to persisted K_pair
 * and the outstanding-request table.
 */
@Singleton
class ApprovalLinkParser @Inject constructor() {

    fun parse(url: String): Outcome<ApprovalEnvelope, com.krypt.app.deeplink.ApprovalError> {
        val parsed = UrlCodec.parse(url)
            ?: return Outcome.err(com.krypt.app.deeplink.ApprovalError.BadScheme)

        if (parsed.authority != DeepLinkScheme.AUTHORITY_APPROVE) {
            return Outcome.err(com.krypt.app.deeplink.ApprovalError.BadScheme)
        }

        val version = parsed.params[Params.VERSION]
            ?: return Outcome.err(com.krypt.app.deeplink.ApprovalError.MissingParam(Params.VERSION))
        if (version != DeepLinkScheme.PROTOCOL_VERSION) {
            return Outcome.err(com.krypt.app.deeplink.ApprovalError.WrongVersion)
        }

        val reqIdString = parsed.params[Params.REQUEST_ID]
            ?: return Outcome.err(com.krypt.app.deeplink.ApprovalError.MissingParam(Params.REQUEST_ID))
        val requestId = try {
            UUID.fromString(reqIdString)
        } catch (_: IllegalArgumentException) {
            return Outcome.err(com.krypt.app.deeplink.ApprovalError.BadUuid)
        }
        if (requestId.version() != 4) {
            return Outcome.err(com.krypt.app.deeplink.ApprovalError.BadUuid)
        }

        val dataB64 = parsed.params[Params.DATA]
            ?: return Outcome.err(com.krypt.app.deeplink.ApprovalError.MissingParam(Params.DATA))
        val data = Base64Url.tryDecode(dataB64)
            ?: return Outcome.err(com.krypt.app.deeplink.ApprovalError.BadBase64)

        // data = nonce(12) || ciphertext(n) || tag(16); minimum is 12 + 16 = 28 with empty plaintext.
        if (data.size < AesGcmCipher.NONCE_BYTES + MIN_CIPHERTEXT_AND_TAG_BYTES) {
            return Outcome.err(com.krypt.app.deeplink.ApprovalError.BadBase64)
        }

        val iatString = parsed.params[Params.ISSUED_AT]
            ?: return Outcome.err(com.krypt.app.deeplink.ApprovalError.MissingParam(Params.ISSUED_AT))
        val iat = iatString.toLongOrNull()
        if (iat == null || iat <= 0) {
            return Outcome.err(com.krypt.app.deeplink.ApprovalError.BadBase64)
        }

        return Outcome.ok(
            ApprovalEnvelope(
                requestId = requestId,
                nonce = data.copyOfRange(0, AesGcmCipher.NONCE_BYTES),
                ciphertextAndTag = data.copyOfRange(AesGcmCipher.NONCE_BYTES, data.size),
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
 * Shape-parsed approval URL. Decryption happens in [ApprovalConsumer].
 */
data class ApprovalEnvelope(
    val requestId: UUID,
    /** 12 bytes. */
    val nonce: ByteArray,
    /** ciphertext || 16-byte tag. */
    val ciphertextAndTag: ByteArray,
    /** Guardian-clock seconds when the approval was issued. */
    val issuedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApprovalEnvelope) return false
        return requestId == other.requestId &&
            nonce.contentEquals(other.nonce) &&
            ciphertextAndTag.contentEquals(other.ciphertextAndTag) &&
            issuedAt == other.issuedAt
    }

    override fun hashCode(): Int {
        var h = requestId.hashCode()
        h = 31 * h + nonce.contentHashCode()
        h = 31 * h + ciphertextAndTag.contentHashCode()
        h = 31 * h + issuedAt.hashCode()
        return h
    }
}
