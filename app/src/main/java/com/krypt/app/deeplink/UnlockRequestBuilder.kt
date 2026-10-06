package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds `krypt://request?...` URLs on the Subject device (Amendment 1).
 *
 * The URL carries the Subject's *setup* salt (from [MasterKeyStore]), the
 * 32-byte `pinProof`, and the PBKDF2 iteration count used at setup, so the
 * Guardian can PBKDF2 a typed PIN with the same salt and work factor and
 * constant-time compare against the embedded proof. This replaces the
 * original WP03 flow, where the URL carried a per-request random salt and
 * validation relied on K_pair.
 *
 * This builder is intentionally PURE (non-suspending): the caller loads
 * `{setupSalt, pinProof}` from [com.krypt.app.security.MasterKeyStore] and
 * passes them in. That keeps the builder trivially JVM-testable and avoids
 * rippling `suspend` through the overlay's "Ask Guardian" button handler.
 */
@Singleton
class UnlockRequestBuilder @Inject constructor(
    private val clock: Clock,
) {

    /**
     * Produce a request URL + its domain object.
     *
     * @param setupSalt     the 16-byte salt persisted at PIN setup time.
     * @param pinProof      the 32-byte `HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")`.
     * @param targetPackage Android package name to ask the Guardian to unlock.
     * @param requestId     defaults to a fresh UUIDv4.
     * @param ttlSeconds    request validity window. Amendment 1 default 300 s.
     * @param kdfIterations PBKDF2 iteration count the MasterKey was derived with.
     *
     * @throws IllegalArgumentException on malformed package name, bad salt /
     *         pinProof sizes, or out-of-range ttl / iteration count.
     */
    fun build(
        setupSalt: ByteArray,
        pinProof: ByteArray,
        targetPackage: String,
        requestId: UUID = UUID.randomUUID(),
        ttlSeconds: Long = UnlockRequest.DEFAULT_TTL_SECONDS,
        kdfIterations: Int = KdfProvider.MIN_ITERATIONS,
    ): Pair<String, UnlockRequest> {
        require(setupSalt.size == UnlockRequest.SALT_BYTES) {
            "setupSalt must be ${UnlockRequest.SALT_BYTES} bytes"
        }
        require(pinProof.size == UnlockRequest.PIN_PROOF_BYTES) {
            "pinProof must be ${UnlockRequest.PIN_PROOF_BYTES} bytes"
        }
        require(targetPackage.matches(PACKAGE_REGEX)) {
            "targetPackage '$targetPackage' is not a valid Android package name"
        }
        require(ttlSeconds in 1..MAX_TTL_SECONDS) {
            "ttlSeconds ($ttlSeconds) must be in 1..$MAX_TTL_SECONDS"
        }
        require(kdfIterations in KdfProvider.MIN_ITERATIONS..KdfProvider.MAX_ITERATIONS) {
            "kdfIterations ($kdfIterations) must be in " +
                "${KdfProvider.MIN_ITERATIONS}..${KdfProvider.MAX_ITERATIONS}"
        }

        val issuedAt = clock.nowSeconds()

        val request = UnlockRequest(
            requestId = requestId,
            targetPackage = targetPackage,
            salt = setupSalt.copyOf(),
            pinProof = pinProof.copyOf(),
            issuedAt = issuedAt,
            ttlSeconds = ttlSeconds,
            kdfIterations = kdfIterations,
            supportsDaily = true,
        )

        val url = UrlCodec.build(
            authority = DeepLinkScheme.AUTHORITY_REQUEST,
            params = listOf(
                Params.VERSION     to DeepLinkScheme.PROTOCOL_VERSION,
                Params.REQUEST_ID  to requestId.toString(),
                Params.APP_PACKAGE to targetPackage,
                Params.SALT        to Base64Url.encode(setupSalt),
                Params.PIN_PROOF   to Base64Url.encode(pinProof),
                Params.KDF_ITER    to kdfIterations.toString(),
                Params.ISSUED_AT   to issuedAt.toString(),
                Params.TTL         to ttlSeconds.toString(),
                Params.CAPS        to DeepLinkScheme.CAP_DAILY,
            ),
        )
        return url to request
    }

    companion object {
        /**
         * Android package name regex per contracts/request.md.
         * Two or more segments, each starts with a letter.
         */
        val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        /** 7 days — longer is almost certainly a bug. */
        const val MAX_TTL_SECONDS = 7 * 24 * 3600L
    }
}
