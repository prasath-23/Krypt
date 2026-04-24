package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.SecureRandomSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Build -> parse round-trips and each [RequestParseError] branch.
 */
class UnlockRequestRoundTripTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val rng = DeterministicRandom(seed = byteArrayOf(1, 2, 3, 4))

    private val builder = UnlockRequestBuilder(rng, clock)
    private val parser = UnlockRequestParser()

    @Test
    fun buildThenParseRoundTrip() {
        val (url, original) = builder.build(
            targetPackage = "com.whatsapp",
            requestId = UUID.fromString("12345678-1234-4abc-8def-123456789abc"),
        )
        val result = parser.parse(url, nowSeconds = clock.nowSeconds())
        val parsed = (result as Outcome.Ok).value

        assertEquals(original.requestId, parsed.requestId)
        assertEquals(original.targetPackage, parsed.targetPackage)
        assertTrue(original.salt.contentEquals(parsed.salt))
        assertEquals(original.issuedAt, parsed.issuedAt)
        assertEquals(original.ttlSeconds, parsed.ttlSeconds)
    }

    @Test
    fun urlSizeUnder512Chars() {
        val (url, _) = builder.build("com.very.long.package.name.with.multiple.segments")
        assertTrue("URL length is ${url.length}", url.length < 512)
    }

    @Test
    fun saltIsExactly16Bytes() {
        val (_, req) = builder.build("com.whatsapp")
        assertEquals(UnlockRequest.SALT_BYTES, req.salt.size)
    }

    @Test
    fun parseRejectsBadScheme() {
        val parsed = parser.parse("https://example.com/not-krypt", clock.nowSeconds())
        assertSame(RequestParseError.BadScheme, (parsed as Outcome.Err).error)
    }

    @Test
    fun parseRejectsWrongAuthority() {
        val parsed = parser.parse("krypt://approve?v=1&req=abc", clock.nowSeconds())
        assertSame(RequestParseError.BadScheme, (parsed as Outcome.Err).error)
    }

    @Test
    fun parseRejectsWrongVersion() {
        val (url, _) = builder.build("com.whatsapp")
        val tampered = url.replace("v=1", "v=999")
        val parsed = parser.parse(tampered, clock.nowSeconds())
        assertSame(RequestParseError.WrongVersion, (parsed as Outcome.Err).error)
    }

    @Test
    fun parseRejectsMissingParam() {
        val (url, _) = builder.build("com.whatsapp")
        // Strip the &salt=... segment.
        val stripped = url.replace(Regex("&salt=[^&]+"), "")
        val parsed = parser.parse(stripped, clock.nowSeconds())
        val err = (parsed as Outcome.Err).error
        assertTrue("expected MissingParam for salt, got $err",
            err is RequestParseError.MissingParam && err.name == "salt")
    }

    @Test
    fun parseRejectsBadBase64Salt() {
        val (url, _) = builder.build("com.whatsapp")
        // Replace salt value with an invalid Base64 token (contains '%' which
        // URL-decodes to a non-Base64 byte, or a literal '!').
        val broken = url.replace(Regex("salt=[^&]+"), "salt=%21%21%21%21")
        val parsed = parser.parse(broken, clock.nowSeconds())
        // Either BadBase64 or BadSaltLength depending on where the decode stops.
        val err = (parsed as Outcome.Err).error
        assertTrue("expected BadBase64 or BadSaltLength, got $err",
            err === RequestParseError.BadBase64 || err === RequestParseError.BadSaltLength)
    }

    @Test
    fun parseRejectsBadUuid() {
        val (url, _) = builder.build("com.whatsapp")
        val broken = url.replace(Regex("req=[^&]+"), "req=not-a-uuid")
        val parsed = parser.parse(broken, clock.nowSeconds())
        assertSame(RequestParseError.BadUuid, (parsed as Outcome.Err).error)
    }

    @Test
    fun parseRejectsNonV4Uuid() {
        val (url, _) = builder.build("com.whatsapp")
        // Replace with a valid-shape UUIDv1 (version digit '1' rather than '4').
        val v1 = "12345678-1234-1abc-8def-123456789abc"
        val broken = url.replace(Regex("req=[^&]+"), "req=$v1")
        val parsed = parser.parse(broken, clock.nowSeconds())
        assertSame(RequestParseError.BadUuid, (parsed as Outcome.Err).error)
    }

    @Test
    fun parseRejectsBadPackageName() {
        val (url, _) = builder.build("com.whatsapp")
        val broken = url.replace("app=com.whatsapp", "app=single")
        val parsed = parser.parse(broken, clock.nowSeconds())
        assertSame(RequestParseError.BadPackageName, (parsed as Outcome.Err).error)
    }

    @Test
    fun parseRejectsExpired() {
        val (url, req) = builder.build("com.whatsapp", ttlSeconds = 60)
        val expiredNow = req.issuedAt + req.ttlSeconds + UnlockRequestParser.CLOCK_SKEW_SECONDS + 10
        val parsed = parser.parse(url, nowSeconds = expiredNow)
        assertSame(RequestParseError.Expired, (parsed as Outcome.Err).error)
    }

    @Test
    fun parseAcceptsWithinClockSkewTolerance() {
        val (url, req) = builder.build("com.whatsapp", ttlSeconds = 60)
        val justPastExpiry = req.issuedAt + req.ttlSeconds + 30  // within the 60s skew budget
        val parsed = parser.parse(url, nowSeconds = justPastExpiry)
        assertTrue("expected Ok within skew budget, got $parsed", parsed is Outcome.Ok)
    }

    @Test
    fun parseRejectsFutureDated() {
        val (url, req) = builder.build("com.whatsapp")
        val farBefore = req.issuedAt - UnlockRequestParser.CLOCK_SKEW_SECONDS - 100
        val parsed = parser.parse(url, nowSeconds = farBefore)
        // When "now" is way before iat, iat > now + skew so BadTimestamp.
        assertSame(RequestParseError.BadTimestamp, (parsed as Outcome.Err).error)
    }

    @Test
    fun builderRejectsInvalidPackageName() {
        try {
            builder.build("no_dot")
            throw AssertionError("should have thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}

// -----------------------------------------------------------------------------
// In-file test doubles — kept here to avoid a shared test-support module.
// -----------------------------------------------------------------------------

internal class FixedClock(var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

internal class DeterministicRandom(seed: ByteArray) : SecureRandomSource {
    private val seedBytes = seed.copyOf()
    private var counter = 0
    override fun nextBytes(size: Int): ByteArray =
        ByteArray(size) { i -> (seedBytes[(counter++) % seedBytes.size].toInt() + i).toByte() }
}
