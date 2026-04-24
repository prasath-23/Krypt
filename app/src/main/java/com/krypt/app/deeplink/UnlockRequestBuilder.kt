package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.crypto.SecureRandomSource
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds `krypt://request?...` URLs on the Subject device.
 *
 * The returned `UnlockRequest` is intended to be persisted into the
 * `outstanding_requests` table (WP05) by the caller, inside the same
 * transaction that opens the Share sheet. See contracts/request.md.
 */
@Singleton
class UnlockRequestBuilder @Inject constructor(
    private val rng: SecureRandomSource,
    private val clock: Clock,
) {

    /**
     * Produce a request URL + its domain object.
     *
     * @param targetPackage Android package name to ask the Guardian to unlock.
     * @param requestId     defaults to a fresh UUIDv4.
     * @param ttlSeconds    request validity window; default 30 min.
     *
     * @throws IllegalArgumentException if [targetPackage] doesn't match the
     *         Android package-name convention from contracts/request.md.
     */
    fun build(
        targetPackage: String,
        requestId: UUID = UUID.randomUUID(),
        ttlSeconds: Long = UnlockRequest.DEFAULT_TTL_SECONDS,
    ): Pair<String, UnlockRequest> {
        require(targetPackage.matches(PACKAGE_REGEX)) {
            "targetPackage '$targetPackage' is not a valid Android package name"
        }
        require(ttlSeconds in 1..MAX_TTL_SECONDS) {
            "ttlSeconds ($ttlSeconds) must be in 1..$MAX_TTL_SECONDS"
        }

        val salt = rng.nextBytes(UnlockRequest.SALT_BYTES)
        val issuedAt = clock.nowSeconds()

        val request = UnlockRequest(
            requestId = requestId,
            targetPackage = targetPackage,
            salt = salt,
            issuedAt = issuedAt,
            ttlSeconds = ttlSeconds,
        )

        val url = UrlCodec.build(
            authority = DeepLinkScheme.AUTHORITY_REQUEST,
            params = listOf(
                Params.VERSION     to DeepLinkScheme.PROTOCOL_VERSION,
                Params.REQUEST_ID  to requestId.toString(),
                Params.APP_PACKAGE to targetPackage,
                Params.SALT        to Base64Url.encode(salt),
                Params.ISSUED_AT   to issuedAt.toString(),
                Params.TTL         to ttlSeconds.toString(),
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
