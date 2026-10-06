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

    /**
     * The one-time payload on the wire, byte for byte, as every Krypt
     * install since Amendment 1 reads it. Changing it would make approvals
     * from this Guardian unreadable on older child phones.
     */
    @Test
    fun oneTimePayload_encodesToTheAmendment1Bytes() {
        val req = "11111111-1111-4111-8111-111111111111"
        val payload = ApprovalPayload(v = "1", req = req, app = "com.example.target", durMin = 15, iat = 1_700_000_000L)

        val expected = cbor(durMin = byteArrayOf(0x0f), req = req)

        assertArrayEquals(expected, ApprovalPayloadCodec.encode(payload))
    }

    @Test
    fun decodeRejectsDurationsOutsideOneMinuteToOneDay() {
        val req = "11111111-1111-4111-8111-111111111111"
        val zero = cbor(durMin = byteArrayOf(0x00), req = req)
        val dayAndAMinute = cbor(durMin = byteArrayOf(0x19, 0x05, 0xA1.toByte()), req = req) // uint16 1441
        val aDay = cbor(durMin = byteArrayOf(0x19, 0x05, 0xA0.toByte()), req = req)          // uint16 1440

        assertThrows(IllegalArgumentException::class.java) { ApprovalPayloadCodec.decode(zero) }
        assertThrows(IllegalArgumentException::class.java) { ApprovalPayloadCodec.decode(dayAndAMinute) }
        assertEquals(1440, ApprovalPayloadCodec.decode(aDay).durMin)
    }

    @Test
    fun payloadRejectsDurationsOutsideOneMinuteToOneDay() {
        assertThrows(IllegalArgumentException::class.java) { samplePayload().copy(durMin = 0) }
        assertThrows(IllegalArgumentException::class.java) { samplePayload().copy(durMin = 24 * 60 + 1) }
    }

    @Test
    fun everyDayPayload_roundTrips_asASixEntryMap() {
        val payload = samplePayload().copy(durMin = 60, days = 7)

        val bytes = ApprovalPayloadCodec.encode(payload)

        assertEquals(0xA6, bytes[0].toInt() and 0xFF)
        assertEquals(payload, ApprovalPayloadCodec.decode(bytes))
        assertEquals(AccessChoice.EveryDay(60, 7), payload.access)
    }

    @Test
    fun decodeRejectsBrokenEveryDayShapes() {
        val req = "11111111-1111-4111-8111-111111111111"
        val five = cbor(durMin = byteArrayOf(0x0f), req = req)
        val sixHeaderFivePairs = byteArrayOf(0xA6.toByte()) + five.copyOfRange(1, five.size)

        for (bad in listOf(
            sixHeaderFivePairs,
            sixHeaderFivePairs + byteArrayOf(0x07, 0x07),                      // key 7, not 6
            sixHeaderFivePairs + byteArrayOf(0x06, 0x00),                      // 0 days
            sixHeaderFivePairs + byteArrayOf(0x06, 0x19, 0x01, 0x6E),          // 366 days
            sixHeaderFivePairs + byteArrayOf(0x06, 0x61, 0x37),                // days as text
            byteArrayOf(0xA7.toByte()) + five.copyOfRange(1, five.size),       // 7 entries
        )) {
            assertThrows(IllegalArgumentException::class.java) { ApprovalPayloadCodec.decode(bad) }
        }
        assertEquals(365, ApprovalPayloadCodec.decode(sixHeaderFivePairs + byteArrayOf(0x06, 0x19, 0x01, 0x6D)).days)
    }

    @Test
    fun aOneTimePayload_hasNoDays() {
        assertEquals(AccessChoice.OneTime(15), samplePayload().access)
    }

    /** The canonical 5-entry map, built by hand from contracts/approve.md. */
    private fun cbor(durMin: ByteArray, req: String): ByteArray {
        val app = "com.example.target"
        return byteArrayOf(0xA5.toByte()) +
            byteArrayOf(0x01, 0x61) + "1".toByteArray() +
            byteArrayOf(0x02, 0x78, req.length.toByte()) + req.toByteArray() +
            byteArrayOf(0x03, (0x60 or app.length).toByte()) + app.toByteArray() +
            byteArrayOf(0x04) + durMin +
            byteArrayOf(0x05, 0x1A, 0x65, 0x53, 0xF1.toByte(), 0x00) // uint32 1_700_000_000
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
