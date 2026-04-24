package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.crypto.KeyDeriver
import com.krypt.app.crypto.SecureRandomSource
import com.krypt.app.deeplink.DeepLinkScheme.Params
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds `krypt://approve?...` URLs on the Guardian device.
 *
 * Wire layout (per contracts/approve.md):
 *   data = nonce(12) || AES-GCM(K_req, CBOR(payload))
 *   K_req = HKDF(K_pair, "krypt/v1/approve", requestId.utf8 || requestSalt)
 *
 * Encryption happens on a pre-matched `UnlockRequest` (recovered by the
 * Guardian from a freshly-received `krypt://request` URL), so the Guardian
 * already has the requestId + salt needed to derive K_req. K_pair is pulled
 * from the Guardian's [com.krypt.app.crypto.KPairStore].
 */
@Singleton
class ApprovalLinkBuilder @Inject constructor(
    private val rng: SecureRandomSource,
    private val clock: Clock,
) {

    /**
     * Build the approval URL.
     *
     * @param kPair                 32-byte shared secret from pairing.
     * @param request               matched UnlockRequest.
     * @param grantDurationMinutes  how long the unlock should last. Default 15.
     */
    fun build(
        kPair: ByteArray,
        request: UnlockRequest,
        grantDurationMinutes: Int = DEFAULT_GRANT_MINUTES,
    ): String {
        require(kPair.size == 32) { "kPair must be 32 bytes (got ${kPair.size})" }
        require(grantDurationMinutes in 1..MAX_GRANT_MINUTES) {
            "grantDurationMinutes ($grantDurationMinutes) must be in 1..$MAX_GRANT_MINUTES"
        }

        val kReq = deriveKReq(kPair, request)
        try {
            val payload = ApprovalPayload(
                v = DeepLinkScheme.PROTOCOL_VERSION,
                req = request.requestId.toString(),
                app = request.targetPackage,
                durMin = grantDurationMinutes,
                iat = clock.nowSeconds(),
            )
            val plaintext = ApprovalPayloadCodec.encode(payload)
            val nonce = rng.nextBytes(AesGcmCipher.NONCE_BYTES)
            val ciphertextAndTag = AesGcmCipher.encrypt(kReq, nonce, plaintext)
            val data = nonce + ciphertextAndTag    // wire layout: nonce || (ct || tag)

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

    /** Derive the per-request AES-256 key. Exposed for test reuse. */
    fun deriveKReq(kPair: ByteArray, request: UnlockRequest): ByteArray {
        val info = request.requestId.toString().toByteArray(Charsets.UTF_8) + request.salt
        return KeyDeriver.hkdfSha256(
            ikm = kPair,
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
    }
}
