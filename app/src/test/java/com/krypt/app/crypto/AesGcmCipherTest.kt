package com.krypt.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Tests for [AesGcmCipher] (AES-256-GCM with 12-byte nonce + 128-bit tag).
 */
class AesGcmCipherTest {

    private val rng = SecureRandom()

    @Test
    fun roundTripSmallPlaintext() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)
        val plaintext = "Hello, Krypt!".toByteArray()

        val ct = AesGcmCipher.encrypt(key, nonce, plaintext)
        assertEquals(
            "ciphertext should be plaintext.size + 16 (tag)",
            plaintext.size + 16,
            ct.size
        )

        val recovered = AesGcmCipher.decrypt(key, nonce, ct)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun roundTripLargePlaintext() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)
        val plaintext = rng.nextBytesArray(8192)

        val ct = AesGcmCipher.encrypt(key, nonce, plaintext)
        val recovered = AesGcmCipher.decrypt(key, nonce, ct)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun roundTripEmptyPlaintext() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)

        val ct = AesGcmCipher.encrypt(key, nonce, ByteArray(0))
        assertEquals(16, ct.size) // tag only
        val recovered = AesGcmCipher.decrypt(key, nonce, ct)
        assertEquals(0, recovered.size)
    }

    @Test
    fun roundTripWithAad() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)
        val plaintext = "secret".toByteArray()
        val aad = "request-id-abc".toByteArray()

        val ct = AesGcmCipher.encrypt(key, nonce, plaintext, aad)
        val recovered = AesGcmCipher.decrypt(key, nonce, ct, aad)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun tamperingCiphertextIsRejected() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)
        val plaintext = "tamper-me".toByteArray()
        val ct = AesGcmCipher.encrypt(key, nonce, plaintext)

        // Flip a bit in the middle of the ciphertext (not the tag).
        val tampered = ct.copyOf()
        tampered[2] = (tampered[2].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.decrypt(key, nonce, tampered)
        }
    }

    @Test
    fun tamperingTagIsRejected() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)
        val plaintext = "tamper-the-tag".toByteArray()
        val ct = AesGcmCipher.encrypt(key, nonce, plaintext)

        // Flip the last byte of the tag.
        val tampered = ct.copyOf()
        val lastIdx = tampered.size - 1
        tampered[lastIdx] = (tampered[lastIdx].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.decrypt(key, nonce, tampered)
        }
    }

    @Test
    fun mismatchedAadIsRejected() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)
        val plaintext = "aad-bound".toByteArray()
        val ct = AesGcmCipher.encrypt(key, nonce, plaintext, "abc".toByteArray())

        assertThrows(AEADBadTagException::class.java) {
            AesGcmCipher.decrypt(key, nonce, ct, "abd".toByteArray())
        }
    }

    @Test
    fun nonceChangeProducesDifferentCiphertext() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val plaintext = "static-plaintext".toByteArray()

        val ctA = AesGcmCipher.encrypt(key, rng.nextBytesArray(AesGcmCipher.NONCE_BYTES), plaintext)
        val ctB = AesGcmCipher.encrypt(key, rng.nextBytesArray(AesGcmCipher.NONCE_BYTES), plaintext)

        assertEquals(
            "ciphertexts with different nonces MUST differ (GCM semantic security)",
            false,
            ctA.contentEquals(ctB)
        )
    }

    @Test
    fun rejectsWrongKeyLength() {
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            AesGcmCipher.encrypt(ByteArray(16), nonce, "hi".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AesGcmCipher.encrypt(ByteArray(24), nonce, "hi".toByteArray())
        }
    }

    @Test
    fun rejectsWrongNonceLength() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            AesGcmCipher.encrypt(key, ByteArray(11), "hi".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AesGcmCipher.encrypt(key, ByteArray(13), "hi".toByteArray())
        }
    }

    @Test
    fun decryptRejectsTruncatedPayload() {
        val key = rng.nextBytesArray(AesGcmCipher.KEY_BYTES)
        val nonce = rng.nextBytesArray(AesGcmCipher.NONCE_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            AesGcmCipher.decrypt(key, nonce, ByteArray(8)) // < 16-byte tag
        }
    }

    private fun SecureRandom.nextBytesArray(size: Int) = ByteArray(size).also(::nextBytes)
}
