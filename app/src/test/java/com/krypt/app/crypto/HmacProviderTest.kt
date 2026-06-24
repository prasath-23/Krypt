package com.krypt.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Correctness tests for [HmacProvider] (Amendment 1 WP19 T102, T104).
 *
 * Uses RFC 4231 Test Case 2 as the primary known-answer test for
 * HMAC-SHA-256. Test Case 2 is the standard "Jefe" / "what do ya want for
 * nothing?" pair and is reproduced verbatim in every HMAC spec since
 * RFC 4231 (Dec 2005). Matching this vector proves both algorithm selection
 * and byte order are correct.
 */
class HmacProviderTest {

    private val hmac = HmacProvider()

    @Test
    fun rfc4231TestCase2_matchesExpectedMac() {
        // RFC 4231 section 4.3 Test Case 2
        val key = "Jefe".toByteArray(Charsets.US_ASCII)
        val data = "what do ya want for nothing?".toByteArray(Charsets.US_ASCII)
        val expected = hexDecode(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        )

        val actual = hmac.sha256(key, data)

        assertArrayEquals("HMAC-SHA-256 must match RFC 4231 TC2", expected, actual)
        assertEquals(HmacProvider.TAG_BYTES, actual.size)
    }

    @Test
    fun outputLengthIsAlways32Bytes() {
        val key = ByteArray(32) { 1 }
        val short = hmac.sha256(key, ByteArray(1))
        val medium = hmac.sha256(key, ByteArray(64))
        val long = hmac.sha256(key, ByteArray(4096))
        assertEquals(32, short.size)
        assertEquals(32, medium.size)
        assertEquals(32, long.size)
    }

    @Test
    fun differentInputsProduceDifferentMacs() {
        val key = "same-key".toByteArray()
        val a = hmac.sha256(key, "alpha".toByteArray())
        val b = hmac.sha256(key, "beta".toByteArray())
        assertArrayEquals(a, hmac.sha256(key, "alpha".toByteArray()))
        // a and b MUST differ with overwhelming probability
        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun differentKeysProduceDifferentMacs() {
        val data = "same-data".toByteArray()
        val a = hmac.sha256("key1".toByteArray(), data)
        val b = hmac.sha256("key2".toByteArray(), data)
        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun emptyKeyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            hmac.sha256(ByteArray(0), "anything".toByteArray())
        }
    }

    private fun hexDecode(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            ((hi shl 4) or lo).toByte()
        }
    }
}
