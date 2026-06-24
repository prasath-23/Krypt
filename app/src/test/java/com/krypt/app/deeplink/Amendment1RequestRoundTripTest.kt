package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Build -> parse round-trips + every [RequestParseError] branch for the
 * Amendment 1 `krypt://request?...` URL shape.
 *
 * The Amendment 1 URL carries:
 *   - setup salt (16 bytes, from MasterKeyStore, NOT per-request)
 *   - pinProof   (32 bytes, HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof"))
 *   - requestId (UUID v4), target package, iat, ttl (default 300 s).
 */
class Amendment1RequestRoundTripTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val builder = UnlockRequestBuilder(clock)
    private val parser = UnlockRequestParser()

    private val setupSalt = ByteArray(UnlockRequest.SALT_BYTES) { it.toByte() }
    private val pinProof = ByteArray(UnlockRequest.PIN_PROOF_BYTES) { (it * 3).toByte() }

    @Test
    fun buildThenParseRoundTrip() {
        val (url, original) = builder.build(
            setupSalt = setupSalt,
            pinProof = pinProof,
            targetPackage = "com.whatsapp",
            requestId = UUID.fromString("12345678-1234-4abc-8def-123456789abc"),
        )
        val parsed = (parser.parse(url, clock.nowSeconds()) as Outcome.Ok).value

        assertEquals(original.requestId, parsed.requestId)
        assertEquals(original.targetPackage, parsed.targetPackage)
        assertArrayEquals(original.salt, parsed.salt)
        assertArrayEquals(original.pinProof, parsed.pinProof)
        assertEquals(original.issuedAt, parsed.issuedAt)
        assertEquals(original.ttlSeconds, parsed.ttlSeconds)
    }

    @Test
    fun defaultTtlIs300Seconds() {
        val (_, request) = builder.build(setupSalt, pinProof, "com.whatsapp")
        assertEquals(300L, request.ttlSeconds)
    }

    @Test
    fun hundredRandomProofsAllRoundTripCleanly() {
        val rng = DeterministicRandom(byteArrayOf(9, 8, 7, 6))
        repeat(100) {
            val salt = rng.nextBytes(UnlockRequest.SALT_BYTES)
            val proof = rng.nextBytes(UnlockRequest.PIN_PROOF_BYTES)
            val (url, original) = builder.build(salt, proof, "com.example.app")
            val parsed = (parser.parse(url, clock.nowSeconds()) as Outcome.Ok).value
            assertArrayEquals(salt, parsed.salt)
            assertArrayEquals(proof, parsed.pinProof)
            assertEquals(original.requestId, parsed.requestId)
        }
    }

    @Test
    fun urlSizeUnder512Chars() {
        val (url, _) = builder.build(
            setupSalt, pinProof, "com.very.long.package.name.with.multiple.segments",
        )
        assertTrue("URL length is ${url.length}", url.length < 512)
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
        val (url, _) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val tampered = url.replace("v=1", "v=999")
        assertSame(
            RequestParseError.WrongVersion,
            (parser.parse(tampered, clock.nowSeconds()) as Outcome.Err).error,
        )
    }

    @Test
    fun parseRejectsMissingPinProof() {
        val (url, _) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val stripped = url.replace(Regex("&pinProof=[^&]+"), "")
        val err = (parser.parse(stripped, clock.nowSeconds()) as Outcome.Err).error
        assertTrue(
            "expected MissingParam(pinProof), got $err",
            err is RequestParseError.MissingParam && err.name == "pinProof",
        )
    }

    @Test
    fun parseRejectsMissingSalt() {
        val (url, _) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val stripped = url.replace(Regex("&salt=[^&]+"), "")
        val err = (parser.parse(stripped, clock.nowSeconds()) as Outcome.Err).error
        assertTrue(
            "expected MissingParam(salt), got $err",
            err is RequestParseError.MissingParam && err.name == "salt",
        )
    }

    @Test
    fun parseRejectsBadBase64Salt() {
        val (url, _) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val broken = url.replace(Regex("salt=[^&]+"), "salt=%21%21%21%21")
        val err = (parser.parse(broken, clock.nowSeconds()) as Outcome.Err).error
        assertTrue(
            "expected BadBase64 or BadSaltLength, got $err",
            err === RequestParseError.BadBase64 || err === RequestParseError.BadSaltLength,
        )
    }

    @Test
    fun parseRejectsWrongLengthPinProof() {
        val (url, _) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val shortProof = Base64Url.encode(ByteArray(16))
        val broken = url.replace(Regex("pinProof=[^&]+"), "pinProof=$shortProof")
        assertSame(
            RequestParseError.BadPinProofLength,
            (parser.parse(broken, clock.nowSeconds()) as Outcome.Err).error,
        )
    }

    @Test
    fun parseRejectsBadUuid() {
        val (url, _) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val broken = url.replace(Regex("req=[^&]+"), "req=not-a-uuid")
        assertSame(
            RequestParseError.BadUuid,
            (parser.parse(broken, clock.nowSeconds()) as Outcome.Err).error,
        )
    }

    @Test
    fun parseRejectsNonV4Uuid() {
        val (url, _) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val v1 = "12345678-1234-1abc-8def-123456789abc"
        val broken = url.replace(Regex("req=[^&]+"), "req=$v1")
        assertSame(
            RequestParseError.BadUuid,
            (parser.parse(broken, clock.nowSeconds()) as Outcome.Err).error,
        )
    }

    @Test
    fun parseRejectsBadPackageName() {
        val (url, _) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val broken = url.replace("app=com.whatsapp", "app=single")
        assertSame(
            RequestParseError.BadPackageName,
            (parser.parse(broken, clock.nowSeconds()) as Outcome.Err).error,
        )
    }

    @Test
    fun parseRejectsExpired() {
        val (url, req) = builder.build(setupSalt, pinProof, "com.whatsapp", ttlSeconds = 60)
        val expiredNow = req.issuedAt + req.ttlSeconds + UnlockRequestParser.CLOCK_SKEW_SECONDS + 10
        assertSame(
            RequestParseError.Expired,
            (parser.parse(url, nowSeconds = expiredNow) as Outcome.Err).error,
        )
    }

    @Test
    fun parseAcceptsWithinClockSkewTolerance() {
        val (url, req) = builder.build(setupSalt, pinProof, "com.whatsapp", ttlSeconds = 60)
        val justPastExpiry = req.issuedAt + req.ttlSeconds + 30 // within the 60s skew budget
        assertTrue(
            "expected Ok within skew budget, got ${parser.parse(url, justPastExpiry)}",
            parser.parse(url, justPastExpiry) is Outcome.Ok,
        )
    }

    @Test
    fun parseRejectsFutureDated() {
        val (url, req) = builder.build(setupSalt, pinProof, "com.whatsapp")
        val farBefore = req.issuedAt - UnlockRequestParser.CLOCK_SKEW_SECONDS - 100
        assertSame(
            RequestParseError.BadTimestamp,
            (parser.parse(url, nowSeconds = farBefore) as Outcome.Err).error,
        )
    }

    @Test
    fun builderRejectsInvalidPackageName() {
        try {
            builder.build(setupSalt, pinProof, "no_dot")
            throw AssertionError("should have thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun builderRejectsBadSaltLength() {
        try {
            builder.build(ByteArray(15), pinProof, "com.whatsapp")
            throw AssertionError("should have thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun builderRejectsBadPinProofLength() {
        try {
            builder.build(setupSalt, ByteArray(31), "com.whatsapp")
            throw AssertionError("should have thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
