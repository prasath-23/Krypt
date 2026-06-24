package com.krypt.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [KeyDeriver] (HKDF-SHA-256, RFC 5869).
 *
 * The RFC 5869 test vectors (sections A.1, A.2, A.3) are authoritative for
 * HKDF-SHA-256. Test Case 1 (A.1) is a basic test with medium-size inputs;
 * Test Case 2 (A.2) uses longer inputs; Test Case 3 (A.3) exercises the
 * empty-salt / empty-info path.
 */
class KeyDeriverTest {

    // RFC 5869 §A.1 — Test Case 1 (SHA-256, basic)
    private val tc1Ikm  = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    private val tc1Salt = hexToBytes("000102030405060708090a0b0c")
    private val tc1Info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
    private val tc1Okm  = hexToBytes(
        "3cb25f25faacd57a90434f64d0362f2a" +
        "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
        "34007208d5b887185865"
    )

    // RFC 5869 §A.2 — Test Case 2 (longer inputs/outputs)
    private val tc2Ikm  = hexToBytes(
        "000102030405060708090a0b0c0d0e0f" +
        "101112131415161718191a1b1c1d1e1f" +
        "202122232425262728292a2b2c2d2e2f" +
        "303132333435363738393a3b3c3d3e3f" +
        "404142434445464748494a4b4c4d4e4f"
    )
    private val tc2Salt = hexToBytes(
        "606162636465666768696a6b6c6d6e6f" +
        "707172737475767778797a7b7c7d7e7f" +
        "808182838485868788898a8b8c8d8e8f" +
        "909192939495969798999a9b9c9d9e9f" +
        "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
    )
    private val tc2Info = hexToBytes(
        "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
        "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
        "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
        "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
        "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
    )
    private val tc2Okm = hexToBytes(
        "b11e398dc80327a1c8e7f78c596a4934" +
        "4f012eda2d4efad8a050cc4c19afa97c" +
        "59045a99cac7827271cb41c65e590e09" +
        "da3275600c2f09b8367793a9aca3db71" +
        "cc30c58179ec3e87c14c01d5c1f3434f" +
        "1d87"
    )

    // RFC 5869 §A.3 — Test Case 3 (zero-length salt and info)
    private val tc3Ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    private val tc3Salt = ByteArray(0)
    private val tc3Info = ByteArray(0)
    private val tc3Okm = hexToBytes(
        "8da4e775a563c18f715f802a063c5a31" +
        "b8a11f5c5ee1879ec3454e5f3c738d2d" +
        "9d201395faa4b61a96c8"
    )

    @Test
    fun testCase1_basic() {
        val okm = KeyDeriver.hkdfSha256(tc1Ikm, tc1Salt, tc1Info, 42)
        assertArrayEquals("RFC 5869 §A.1 mismatch", tc1Okm, okm)
    }

    @Test
    fun testCase2_longInputs() {
        val okm = KeyDeriver.hkdfSha256(tc2Ikm, tc2Salt, tc2Info, 82)
        assertArrayEquals("RFC 5869 §A.2 mismatch", tc2Okm, okm)
    }

    @Test
    fun testCase3_emptySaltAndInfo() {
        // RFC 5869 §2.2 specifies an empty salt is treated as HashLen zero bytes.
        val okm = KeyDeriver.hkdfSha256(tc3Ikm, tc3Salt, tc3Info, 42)
        assertArrayEquals("RFC 5869 §A.3 mismatch", tc3Okm, okm)
    }

    @Test
    fun rejectsOutLengthAboveMax() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDeriver.hkdfSha256(tc1Ikm, tc1Salt, tc1Info, 255 * 32 + 1)
        }
    }

    @Test
    fun rejectsOutLengthZeroOrNegative() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDeriver.hkdfSha256(tc1Ikm, tc1Salt, tc1Info, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyDeriver.hkdfSha256(tc1Ikm, tc1Salt, tc1Info, -1)
        }
    }

    @Test
    fun outputLengthMatches() {
        for (len in listOf(1, 16, 32, 33, 64, 100, 255 * 32)) {
            assertEquals(
                "output length mismatch for requested=$len",
                len,
                KeyDeriver.hkdfSha256(tc1Ikm, tc1Salt, tc1Info, len).size
            )
        }
    }

    @Test
    fun differentInfoProducesDifferentOutput() {
        val a = KeyDeriver.hkdfSha256(tc1Ikm, tc1Salt, "context-a".toByteArray(), 32)
        val b = KeyDeriver.hkdfSha256(tc1Ikm, tc1Salt, "context-b".toByteArray(), 32)
        assertTrue("different info must produce different OKM", !a.contentEquals(b))
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
