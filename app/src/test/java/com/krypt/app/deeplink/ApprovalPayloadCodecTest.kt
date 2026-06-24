package com.krypt.app.deeplink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.UUID

/**
 * Hand-rolled CBOR encode/decode for [ApprovalPayload].
 */
class ApprovalPayloadCodecTest {

    private fun samplePayload() = ApprovalPayload(
        v = "1",
        req = UUID.randomUUID().toString(),
        app = "com.example.target",
        durMin = 15,
        iat = 1_700_000_000L,
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val original = samplePayload()
        val bytes = ApprovalPayloadCodec.encode(original)
        val restored = ApprovalPayloadCodec.decode(bytes)
        assertEquals(original, restored)
    }

    @Test
    fun encodedStartsWithMapHeader0xA5() {
        val encoded = ApprovalPayloadCodec.encode(samplePayload())
        assertEquals("first byte must be CBOR map-of-5 marker",
            0xA5, encoded[0].toInt() and 0xFF)
    }

    @Test
    fun encodedSizeUnder200Bytes() {
        val encoded = ApprovalPayloadCodec.encode(samplePayload())
        assertTrue("encoded size is ${encoded.size}", encoded.size <= 200)
    }

    @Test
    fun propertyBased100RandomPayloadsRoundTrip() {
        val rng = Random(0x51CEDEADL)
        repeat(100) { i ->
            val payload = ApprovalPayload(
                v = "1",
                req = UUID.randomUUID().toString(),
                app = randomPackage(rng),
                durMin = rng.nextInt(1, 24 * 60),
                iat = 1_000_000_000L + rng.nextLong() and 0x7FFFFFFFL,
            )
            val bytes = ApprovalPayloadCodec.encode(payload)
            val restored = ApprovalPayloadCodec.decode(bytes)
            assertEquals("round-trip mismatch at iteration $i", payload, restored)
        }
    }

    @Test
    fun decodeRejectsEmpty() {
        assertThrows(IllegalArgumentException::class.java) {
            ApprovalPayloadCodec.decode(ByteArray(0))
        }
    }

    @Test
    fun decodeRejectsWrongMapHeader() {
        val good = ApprovalPayloadCodec.encode(samplePayload())
        val wrong = good.copyOf()
        wrong[0] = 0xA4.toByte()  // map-of-4 instead of map-of-5
        assertThrows(IllegalArgumentException::class.java) {
            ApprovalPayloadCodec.decode(wrong)
        }
    }

    @Test
    fun decodeRejectsTruncated() {
        val good = ApprovalPayloadCodec.encode(samplePayload())
        val truncated = good.copyOfRange(0, good.size - 1)
        assertThrows(IllegalArgumentException::class.java) {
            ApprovalPayloadCodec.decode(truncated)
        }
    }

    @Test
    fun decodeRejectsTrailingBytes() {
        val good = ApprovalPayloadCodec.encode(samplePayload())
        val padded = good + byteArrayOf(0x00, 0x00)
        assertThrows(IllegalArgumentException::class.java) {
            ApprovalPayloadCodec.decode(padded)
        }
    }

    @Test
    fun decodeRejectsOutOfOrderKeys() {
        // Encode manually with keys 2, 1, 3, 4, 5 (swapped v/req).
        val swapped = byteArrayOf(
            0xA5.toByte(),
            // key 2, text "req-value"
            0x02, 0x69, 'r'.code.toByte(), 'e'.code.toByte(), 'q'.code.toByte(),
            '-'.code.toByte(), 'v'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
            'u'.code.toByte(), 'e'.code.toByte(),
            // key 1, text "1"
            0x01, 0x61, '1'.code.toByte(),
            // key 3, text "x"
            0x03, 0x61, 'x'.code.toByte(),
            // key 4, uint 1
            0x04, 0x01,
            // key 5, uint 1
            0x05, 0x01,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ApprovalPayloadCodec.decode(swapped)
        }
    }

    @Test
    fun largeIatEncodesAsUint64() {
        val payload = samplePayload().copy(iat = 5_000_000_000L) // > 2^32
        val encoded = ApprovalPayloadCodec.encode(payload)
        val restored = ApprovalPayloadCodec.decode(encoded)
        assertEquals(payload.iat, restored.iat)
    }

    @Test
    fun encodedBytesAreDeterministicForSameInput() {
        val p = samplePayload()
        assertArrayEquals(
            ApprovalPayloadCodec.encode(p),
            ApprovalPayloadCodec.encode(p),
        )
    }

    private fun randomPackage(rng: Random): String {
        val parts = rng.nextInt(2, 5)
        return (0 until parts).joinToString(".") {
            val len = rng.nextInt(3, 8)
            (0 until len).map { ('a' + rng.nextInt(26)) }.joinToString("")
        }
    }

    private fun Random.nextInt(origin: Int, bound: Int): Int =
        origin + this.nextInt(bound - origin)

    private fun Random.nextLong(): Long {
        val hi = this.nextInt().toLong() and 0xFFFFFFFFL
        val lo = this.nextInt().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }
}
