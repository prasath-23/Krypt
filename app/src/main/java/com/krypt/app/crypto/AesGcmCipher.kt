package com.krypt.app.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM authenticated encryption.
 *
 * Used in Krypt to seal the approval-link payload (per
 * polaris-specs/001-krypt-app-locker/contracts/approve.md):
 *
 *   data = nonce(12) || AES-GCM(K_req, plaintext, tag_bits=128)
 *
 * Invariants the caller must uphold:
 *   - key length   = 32 bytes (AES-256)
 *   - nonce length = 12 bytes (JCE / NIST standard for GCM)
 *   - tag bits     = 128 (default; no truncation)
 *
 * Nonce reuse under the same key is catastrophic for GCM (it leaks the
 * authentication key and permits forgeries). We deliberately refuse to
 * generate nonces here — every caller must produce a fresh random 12-byte
 * nonce from [SecureRandomSource]. The returned byte array contains only
 * `ciphertext || tag`; callers attach the nonce to their own wire layout.
 *
 * On tamper (flipped bit in ciphertext, tag, nonce, AAD, or key) [decrypt]
 * throws [javax.crypto.AEADBadTagException] — catch it at the parse boundary,
 * never surface the exception's message to the user.
 */
object AesGcmCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12

    /**
     * Encrypt [plaintext]; returns `ciphertext || tag` (plaintext.length + 16 bytes).
     *
     * @throws IllegalArgumentException on wrong-sized key or nonce.
     */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        requireKeyAndNonce(key, nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, KEY_ALGORITHM),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypt [ciphertextAndTag] (which must be `ciphertext || tag`).
     *
     * @throws IllegalArgumentException on wrong-sized key, nonce, or too-short payload.
     * @throws javax.crypto.AEADBadTagException on any tamper.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        requireKeyAndNonce(key, nonce)
        require(ciphertextAndTag.size >= TAG_BYTES) {
            "ciphertextAndTag must be at least $TAG_BYTES bytes (tag length)"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, KEY_ALGORITHM),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }

    private fun requireKeyAndNonce(key: ByteArray, nonce: ByteArray) {
        require(key.size == KEY_BYTES) {
            "key must be $KEY_BYTES bytes (got ${key.size})"
        }
        require(nonce.size == NONCE_BYTES) {
            "nonce must be $NONCE_BYTES bytes (got ${nonce.size})"
        }
    }
}
