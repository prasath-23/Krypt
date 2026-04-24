package com.krypt.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF with HMAC-SHA-256 (RFC 5869).
 *
 * Krypt uses this to derive per-request AES-256 keys from the long-term
 * pairing secret K_pair:
 *
 *   K_req = HKDF(ikm = K_pair, salt = "krypt/v1/approve",
 *                info = requestId_utf8 || reqSalt)
 *
 * See polaris-specs/001-krypt-app-locker/contracts/approve.md for the exact
 * derivation rule.
 *
 * The two-step structure follows RFC 5869 section 2:
 *   - Extract: PRK = HMAC-SHA-256(salt, ikm)
 *   - Expand:  T(1)..T(N) = HMAC-SHA-256(PRK, T(i-1) || info || i)
 *
 * Maximum output length is 255 * HashLen = 255 * 32 = 8160 bytes. Callers
 * that need more than that must chain HKDF, which we never do.
 */
object KeyDeriver {

    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32
    private const val MAX_OKM_LEN = 255 * HASH_LEN

    /**
     * Derive [outLength] bytes of output keying material.
     *
     * @param ikm        input keying material (e.g. K_pair)
     * @param salt       HKDF salt; per RFC 5869 §2.2 an empty salt is replaced
     *                   by a zero-filled buffer of HashLen bytes.
     * @param info       context / application label (e.g. "krypt/v1/approve")
     * @param outLength  desired output bytes (1..8160)
     *
     * @throws IllegalArgumentException if outLength is out of range.
     */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outLength: Int
    ): ByteArray {
        require(outLength in 1..MAX_OKM_LEN) {
            "outLength ($outLength) must be in 1..$MAX_OKM_LEN (RFC 5869 §2.3)"
        }

        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt

        // --- Extract ------------------------------------------------------
        val prk = hmac(effectiveSalt, ikm)
        try {
            // --- Expand -------------------------------------------------------
            val out = ByteArray(outLength)
            var position = 0
            var prev = ByteArray(0)
            var counter = 1
            while (position < outLength) {
                val macInput = ByteArray(prev.size + info.size + 1).apply {
                    System.arraycopy(prev, 0, this, 0, prev.size)
                    System.arraycopy(info, 0, this, prev.size, info.size)
                    this[this.size - 1] = counter.toByte()
                }
                val t = hmac(prk, macInput)
                val chunk = minOf(t.size, outLength - position)
                System.arraycopy(t, 0, out, position, chunk)
                position += chunk
                prev = t
                counter++
            }
            return out
        } finally {
            // Zero the PRK: although it's not a long-term key, a heap dump
            // during OKM derivation would reveal it, letting an attacker
            // rederive all HKDF outputs without re-running Extract.
            prk.fill(0)
        }
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(message)
    }
}
