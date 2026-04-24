package com.krypt.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA-256 convenience wrapper.
 *
 * Used by [com.krypt.app.deeplink.PairedReplyBuilder] to authenticate the
 * `krypt://paired` URL against K_pair, and by
 * [com.krypt.app.deeplink.PairedReplyVerifier] to validate it on the Subject
 * side.
 */
object HmacSha256 {

    private const val ALGORITHM = "HmacSHA256"

    /** Tag length in bytes (HMAC-SHA-256 always produces 32 bytes). */
    const val TAG_BYTES = 32

    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return mac.doFinal(message)
    }
}
