package com.krypt.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt-injectable HMAC-SHA-256 wrapper (Amendment 1).
 *
 * Added alongside the pre-existing [HmacSha256] object because the Amendment 1
 * work packages (WP19/WP21) take HMAC via constructor-injection so they can be
 * tested with a fake Mac, while WP03/WP04 callers continue to reach for the
 * simpler static [HmacSha256] helper.
 *
 * Verified by an RFC 4231 known-answer test (WP19 T104).
 */
@Singleton
class HmacProvider @Inject constructor() {

    /**
     * Return HMAC-SHA-256([data]) under [key]. Output is always 32 bytes.
     *
     * @throws IllegalArgumentException if [key] is empty. (JCE will accept a
     * zero-length key but it gives trivially-forgeable tags, so we guard.)
     */
    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "key MUST NOT be empty" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return mac.doFinal(data)
    }

    companion object {
        const val ALGORITHM = "HmacSHA256"

        /** HMAC-SHA-256 output length in bytes. */
        const val TAG_BYTES = 32
    }
}
