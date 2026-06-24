package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.crypto.KeyDeriver
import com.krypt.app.crypto.SecureRandomSource
import com.krypt.app.deeplink.DeepLinkScheme.Params
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds `krypt://approve?...` URLs on the Guardian device (Amendment 1).
 *
 * Wire layout (per contracts/approve.md, post-Amendment 1):
 *   data = nonceForHkdf(16) || aesNonce(12) || AES-256-GCM(K_req, CBOR(payload))
 *   K_req = HKDF-SHA-256(
 *     ikm  = MasterKey,
 *     salt = "krypt/v1/approve".utf8,
 *     info = requestId.utf8 || nonceForHkdf,
 *     L    = 32,
 *   )
 *
 * `MasterKey` is 32 bytes, derived from the typed PIN + setup salt (on the
 * Guardian's device when it validates the PIN, or on the Subject's device
 * where it was computed once at setup and stored in `MasterKeyStore`). The
 * 16-byte `nonceForHkdf` is fresh per approval and is how the key is bound
 * to this specific approval; it is PART OF the `data` blob so the Subject
 * can re-derive K_req before decryption.
 *
 * `K_pair` (X25519 shared secret) is no longer used - the shipped
 * [com.krypt.app.crypto.KPairStore] remains compiled for legacy pairing
 * code but has no callers in the live Amendment 1 flow.
 */
@Singleton
class ApprovalLinkBuilder @Inject constructor(
    private val rng: SecureRandomSource,
    private val clock: Clock,
) {

    /**
     * Build the approval URL.
     *
     * @param masterKey             32-byte MasterKey (PBKDF2 derived).
     * @param request               matched UnlockRequest.
     * @param grantDurationMinutes  how long the unlock should last. Default 15.
     */
    fun build(
        masterKey: ByteArray,
        request: UnlockRequest,
        grantDurationMinutes: Int = DEFAULT_GRANT_MINUTES,
    ): String {
        require(masterKey.size == AesGcmCipher.KEY_BYTES) {
            "masterKey must be ${AesGcmCipher.KEY_BYTES} bytes (got ${masterKey.size})"
        }
        require(grantDurationMinutes in 1..MAX_GRANT_MINUTES) {
            "grantDurationMinutes ($grantDurationMinutes) must be in 1..$MAX_GRANT_MINUTES"
        }

        val nonceForHkdf = rng.nextBytes(NONCE_FOR_HKDF_BYTES)
        val kReq = deriveKReq(masterKey, request.requestId.toString(), nonceForHkdf)
        try {
            val payload = ApprovalPayload(
                v = DeepLinkScheme.PROTOCOL_VERSION,
                req = request.requestId.toString(),
                app = request.targetPackage,
                durMin = grantDurationMinutes,
                iat = clock.nowSeconds(),
            )
            val plaintext = ApprovalPayloadCodec.encode(payload)
            val aesNonce = rng.nextBytes(AesGcmCipher.NONCE_BYTES)
            val ciphertextAndTag = AesGcmCipher.encrypt(kReq, aesNonce, plaintext)
            val data = nonceForHkdf + aesNonce + ciphertextAndTag

            return UrlCodec.build(
                authority = DeepLinkScheme.AUTHORITY_APPROVE,
                params = listOf(
                    Params.VERSION    to DeepLinkScheme.PROTOCOL_VERSION,
                    Params.REQUEST_ID to request.requestId.toString(),
                    Params.DATA       to Base64Url.encode(data),
                    Params.ISSUED_AT  to payload.iat.toString(),
                ),
            )
        } finally {
            kReq.fill(0)
        }
    }

    /**
     * Derive the per-request AES-256 key from a [masterKey] (32 bytes) and
     * the request-specific [nonceForHkdf] (16 bytes). Exposed so the
     * consumer side ([ApprovalConsumer]) can re-derive identically.
     */
    fun deriveKReq(
        masterKey: ByteArray,
        requestIdUtf8: String,
        nonceForHkdf: ByteArray,
    ): ByteArray {
        require(masterKey.size == AesGcmCipher.KEY_BYTES)
        require(nonceForHkdf.size == NONCE_FOR_HKDF_BYTES)
        val info = requestIdUtf8.toByteArray(Charsets.UTF_8) + nonceForHkdf
        return KeyDeriver.hkdfSha256(
            ikm = masterKey,
            salt = HKDF_SALT,
            info = info,
            outLength = AesGcmCipher.KEY_BYTES,
        )
    }

    companion object {
        const val DEFAULT_GRANT_MINUTES: Int = 15
        const val MAX_GRANT_MINUTES: Int = 24 * 60     // one day ceiling

        /** HKDF salt label for the per-request AES key derivation. */
        val HKDF_SALT: ByteArray = "krypt/v1/approve".toByteArray(Charsets.UTF_8)

        /**
         * Amendment 1: 16-byte per-approval nonce used as HKDF info material.
         * Prepended to the `data` blob so the Subject can re-derive K_req
         * before decryption.
         */
        const val NONCE_FOR_HKDF_BYTES = 16
    }
}
