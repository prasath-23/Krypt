package com.krypt.app.deeplink

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Hand-rolled CBOR encoder / decoder for [ApprovalPayload].
 *
 * Deliberately minimal — we target only the exact 5-field map shape defined
 * in contracts/approve.md. Any CBOR deviation (extra fields, wrong keys,
 * out-of-order keys, trailing bytes, wrong major types) triggers an
 * [IllegalArgumentException] at decode time.
 *
 * Why hand-rolled: no first-party CBOR library is available, and pulling in
 * a third-party one (jackson-dataformat-cbor, cbor-java) violates the
 * "no third-party data libraries" rule.
 *
 * Canonical shape (in encode order):
 *   0xa5                 ; map with 5 entries
 *   01 <text "1">        ; v
 *   02 <text "<uuid>">   ; req
 *   03 <text "<pkg>">    ; app
 *   04 <uint durMin>     ; durMin
 *   05 <uint iat>        ; iat
 */
internal object ApprovalPayloadCodec {

    /** CBOR major types, pre-shifted into the top 3 bits. */
    private const val MT_UNSIGNED_INT: Int = 0x00
    private const val MT_TEXT: Int = 0x60
    private const val MT_MAP: Int = 0xA0

    private const val MAP_HEADER_5: Byte = (MT_MAP or 5).toByte()   // 0xa5

    fun encode(payload: ApprovalPayload): ByteArray {
        val out = ByteArrayOutputStream(96)
        out.write(MAP_HEADER_5.toInt() and 0xFF)
        writeKeyAndText(out, key = 1, value = payload.v)
        writeKeyAndText(out, key = 2, value = payload.req)
        writeKeyAndText(out, key = 3, value = payload.app)
        writeKeyAndUnsigned(out, key = 4, value = payload.durMin.toLong())
        writeKeyAndUnsigned(out, key = 5, value = payload.iat)
        return out.toByteArray()
    }

    /**
     * Decode exactly the canonical shape. Any deviation throws.
     *
     * @throws IllegalArgumentException on header mismatch, wrong key order,
     *         negative values, truncated input, or trailing bytes.
     */
    fun decode(bytes: ByteArray): ApprovalPayload {
        val reader = Reader(bytes)
        val header = reader.readByte()
        require(header == MAP_HEADER_5) {
            "expected CBOR map of 5 entries (0xa5); got 0x%02x".format(header.toInt() and 0xFF)
        }
        val v      = readKeyAndText(reader, expectedKey = 1)
        val req    = readKeyAndText(reader, expectedKey = 2)
        val app    = readKeyAndText(reader, expectedKey = 3)
        val durMin = readKeyAndUnsigned(reader, expectedKey = 4)
        val iat    = readKeyAndUnsigned(reader, expectedKey = 5)
        reader.requireExhausted()

        require(durMin in 1..Int.MAX_VALUE) { "durMin out of range: $durMin" }
        require(iat > 0) { "iat must be positive" }

        return ApprovalPayload(
            v = v,
            req = req,
            app = app,
            durMin = durMin.toInt(),
            iat = iat,
        )
    }

    // ---------- encode helpers ----------

    private fun writeKeyAndText(out: ByteArrayOutputStream, key: Int, value: String) {
        writeUnsigned(out, MT_UNSIGNED_INT, key.toLong())
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        writeUnsigned(out, MT_TEXT, utf8.size.toLong())
        out.write(utf8)
    }

    private fun writeKeyAndUnsigned(out: ByteArrayOutputStream, key: Int, value: Long) {
        writeUnsigned(out, MT_UNSIGNED_INT, key.toLong())
        writeUnsigned(out, MT_UNSIGNED_INT, value)
    }

    private fun writeUnsigned(out: ByteArrayOutputStream, majorType: Int, value: Long) {
        require(value >= 0) { "unsigned CBOR value must be non-negative: $value" }
        when {
            value <= 23L -> out.write(majorType or value.toInt())
            value <= 0xFFL -> {
                out.write(majorType or 0x18)
                out.write(value.toInt() and 0xFF)
            }
            value <= 0xFFFFL -> {
                out.write(majorType or 0x19)
                out.write(((value ushr 8).toInt()) and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            value <= 0xFFFFFFFFL -> {
                out.write(majorType or 0x1A)
                for (shift in 24 downTo 0 step 8) {
                    out.write(((value ushr shift).toInt()) and 0xFF)
                }
            }
            else -> {
                out.write(majorType or 0x1B)
                for (shift in 56 downTo 0 step 8) {
                    out.write(((value ushr shift).toInt()) and 0xFF)
                }
            }
        }
    }

    // ---------- decode helpers ----------

    private class Reader(private val data: ByteArray) {
        private var pos: Int = 0

        fun readByte(): Byte {
            require(pos < data.size) { "unexpected end of CBOR input at pos=$pos" }
            return data[pos++]
        }

        fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

        fun readBytes(count: Int): ByteArray {
            require(count >= 0) { "negative read length: $count" }
            require(pos + count <= data.size) {
                "unexpected end of CBOR input (want $count bytes at pos=$pos, have ${data.size - pos})"
            }
            val copy = data.copyOfRange(pos, pos + count)
            pos += count
            return copy
        }

        fun requireExhausted() {
            require(pos == data.size) {
                "CBOR payload has ${data.size - pos} trailing bytes at pos=$pos"
            }
        }
    }

    private fun readKeyAndText(reader: Reader, expectedKey: Int): String {
        val key = readUnsigned(reader, MT_UNSIGNED_INT)
        require(key == expectedKey.toLong()) {
            "expected CBOR key $expectedKey, got $key"
        }
        return readText(reader)
    }

    private fun readKeyAndUnsigned(reader: Reader, expectedKey: Int): Long {
        val key = readUnsigned(reader, MT_UNSIGNED_INT)
        require(key == expectedKey.toLong()) {
            "expected CBOR key $expectedKey, got $key"
        }
        return readUnsigned(reader, MT_UNSIGNED_INT)
    }

    private fun readUnsigned(reader: Reader, expectedMajor: Int): Long {
        val header = reader.readUnsignedByte()
        val major = header and 0xE0
        require(major == expectedMajor) {
            "expected CBOR major type 0x%02x, got 0x%02x".format(expectedMajor, major)
        }
        val info = header and 0x1F
        return when {
            info <= 23 -> info.toLong()
            info == 24 -> reader.readUnsignedByte().toLong()
            info == 25 -> {
                val b1 = reader.readUnsignedByte()
                val b2 = reader.readUnsignedByte()
                (b1.toLong() shl 8) or b2.toLong()
            }
            info == 26 -> {
                var value = 0L
                repeat(4) { value = (value shl 8) or reader.readUnsignedByte().toLong() }
                value
            }
            info == 27 -> {
                var value = 0L
                repeat(8) { value = (value shl 8) or reader.readUnsignedByte().toLong() }
                // We never produce values > Long.MAX_VALUE, so no sign-bit ambiguity.
                require(value >= 0) { "CBOR 8-byte unsigned overflowed signed long" }
                value
            }
            else -> error("unsupported CBOR length-info $info (indefinite-length not used)")
        }
    }

    private fun readText(reader: Reader): String {
        val length = readUnsigned(reader, MT_TEXT)
        require(length in 0..MAX_TEXT_BYTES) {
            "CBOR text too long: $length bytes"
        }
        return reader.readBytes(length.toInt()).toString(StandardCharsets.UTF_8)
    }

    /** 4 KB — absurdly generous for our 5-field schema but keeps decode bounded. */
    private const val MAX_TEXT_BYTES: Long = 4096L
}
