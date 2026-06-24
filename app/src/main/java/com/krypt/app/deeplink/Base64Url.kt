package com.krypt.app.deeplink

import java.util.Base64

/**
 * URL-safe, padding-less Base64 codec.
 *
 * Backed by `java.util.Base64` (JVM-standard since JDK 8). Produces bytes
 * identical to `android.util.Base64` with the flag combination
 * `URL_SAFE | NO_WRAP | NO_PADDING` — both implementations follow RFC 4648
 * §5. Using java.util.Base64 here means this class is usable from pure JVM
 * unit tests without Robolectric.
 */
object Base64Url {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** Encode [bytes] as URL-safe Base64 with no padding. */
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * Decode [text] as URL-safe Base64 (padding optional).
     *
     * @throws IllegalArgumentException if [text] is not valid Base64.
     */
    fun decode(text: String): ByteArray = decoder.decode(text)

    /**
     * Try to decode [text]; returns `null` if decoding throws. Parsers use
     * this rather than catching [IllegalArgumentException] at every call site.
     */
    fun tryDecode(text: String): ByteArray? = try {
        decoder.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
