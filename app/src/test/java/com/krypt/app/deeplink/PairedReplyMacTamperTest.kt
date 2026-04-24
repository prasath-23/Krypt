package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.X25519KeyAgreement
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.util.UUID

/**
 * Bit-flip sensitivity: flipping any MAC-covered byte must be caught by
 * [PairedReplyVerifier.parseAndVerify] as [PairedParseError.BadMac] (or an
 * equivalent shape-level error if the flip breaks encoding).
 */
class PairedReplyMacTamperTest {

    @Before
    fun assumeXdhAvailable() {
        try { KeyFactory.getInstance("XDH") } catch (t: Throwable) {
            assumeNoException("XDH not available", t)
        }
    }

    private val clock = FixedClockP(1_700_000_000_000L)
    private val pairBuilder = PairRequestBuilder(clock)
    private val pairParser = PairRequestParser()
    private val pairedBuilder = PairedReplyBuilder(clock)
    private val verifier = PairedReplyVerifier()

    private fun freshPairing(): Triple<UUID, java.security.PrivateKey, String> {
        val subjectKp = X25519KeyAgreement.generateEphemeralKeyPair()
        val subjectId = UUID.fromString("33345678-1234-4abc-8def-123456789abc")
        val (pairUrl, _) = pairBuilder.build(subjectId, "Alice", subjectKp)
        val parsed = (pairParser.parse(pairUrl, clock.nowSeconds()) as Outcome.Ok).value

        val guardianKp = X25519KeyAgreement.generateEphemeralKeyPair()
        val (pairedUrl, _) = pairedBuilder.buildWithFreshAgreement(
            incoming = parsed,
            guardianDisplayName = "G",
            guardianEphKeyPair = guardianKp,
            guardianPubSalt = ByteArray(32) { (it + 1).toByte() },
            guardianKdfIterations = 400_000,
        )
        return Triple(subjectId, subjectKp.private, pairedUrl)
    }

    @Test
    fun flipMacByteYieldsBadMac() {
        val (subjectId, priv, url) = freshPairing()
        // Flip a bit in the mac= value.
        val tampered = url.replace(Regex("mac=[^&]+")) { m ->
            val b64 = m.value.substringAfter("mac=")
            val bytes = Base64Url.decode(b64)
            bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
            "mac=${Base64Url.encode(bytes)}"
        }
        val out = verifier.parseAndVerify(tampered, clock.nowSeconds() + 10, priv, subjectId)
        assertSame(PairedParseError.BadMac, (out as Outcome.Err).error)
    }

    @Test
    fun flipPubSaltYieldsBadMac() {
        val (subjectId, priv, url) = freshPairing()
        val tampered = url.replace(Regex("pubSalt=[^&]+")) { m ->
            val b64 = m.value.substringAfter("pubSalt=")
            val bytes = Base64Url.decode(b64)
            bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
            "pubSalt=${Base64Url.encode(bytes)}"
        }
        val out = verifier.parseAndVerify(tampered, clock.nowSeconds() + 10, priv, subjectId)
        assertSame(PairedParseError.BadMac, (out as Outcome.Err).error)
    }

    @Test
    fun flipEphPubYieldsBadMacOrBadCrypto() {
        // Flipping ephPub changes the Subject's derived K_pair, so MAC won't
        // match (BadMac). Happens to route through X25519 agreement which
        // accepts any 32-byte public key, so no BadKeyLength.
        val (subjectId, priv, url) = freshPairing()
        val tampered = url.replace(Regex("ephPub=[^&]+")) { m ->
            val b64 = m.value.substringAfter("ephPub=")
            val bytes = Base64Url.decode(b64)
            bytes[5] = (bytes[5].toInt() xor 0x01).toByte()
            "ephPub=${Base64Url.encode(bytes)}"
        }
        val out = verifier.parseAndVerify(tampered, clock.nowSeconds() + 10, priv, subjectId)
        assertSame(PairedParseError.BadMac, (out as Outcome.Err).error)
    }

    @Test
    fun flipIatYieldsBadMac() {
        val (subjectId, priv, url) = freshPairing()
        val tampered = url.replace(Regex("iat=[^&]+")) { m ->
            val v = m.value.substringAfter("iat=").toLong()
            "iat=${v + 1}"
        }
        val out = verifier.parseAndVerify(tampered, clock.nowSeconds() + 10, priv, subjectId)
        assertSame(PairedParseError.BadMac, (out as Outcome.Err).error)
    }

    @Test
    fun flipTtlYieldsBadMac() {
        val (subjectId, priv, url) = freshPairing()
        val tampered = url.replace(Regex("(?<![a-z])ttl=[^&]+")) { m ->
            val v = m.value.substringAfter("ttl=").toLong()
            "ttl=${v + 1}"
        }
        val out = verifier.parseAndVerify(tampered, clock.nowSeconds() + 10, priv, subjectId)
        assertSame(PairedParseError.BadMac, (out as Outcome.Err).error)
    }

    @Test
    fun flipKdfIterYieldsBadMac() {
        val (subjectId, priv, url) = freshPairing()
        val tampered = url.replace(Regex("kdfIter=[^&]+")) { m ->
            val v = m.value.substringAfter("kdfIter=").toInt()
            "kdfIter=${v + 1}"
        }
        val out = verifier.parseAndVerify(tampered, clock.nowSeconds() + 10, priv, subjectId)
        assertSame(PairedParseError.BadMac, (out as Outcome.Err).error)
    }

    @Test
    fun wrongSubjectIdYieldsWrongSubjectId() {
        val (_, priv, url) = freshPairing()
        val other = UUID.fromString("99999999-1234-4abc-8def-123456789abc")
        val out = verifier.parseAndVerify(url, clock.nowSeconds() + 10, priv, other)
        assertSame(PairedParseError.WrongSubjectId, (out as Outcome.Err).error)
    }

    @Test
    fun expiredReplyYieldsExpired() {
        val (subjectId, priv, url) = freshPairing()
        val tooLate = clock.nowSeconds() + PairedReply.DEFAULT_TTL_SECONDS +
            PairedReplyVerifier.CLOCK_SKEW_SECONDS + 100
        val out = verifier.parseAndVerify(url, tooLate, priv, subjectId)
        assertSame(PairedParseError.Expired, (out as Outcome.Err).error)
    }

    @Test
    fun invalidBase64InMacYieldsBadBase64() {
        val (subjectId, priv, url) = freshPairing()
        val tampered = url.replace(Regex("mac=[^&]+"), "mac=%21%21%21%21")
        val out = verifier.parseAndVerify(tampered, clock.nowSeconds() + 10, priv, subjectId)
        val err = (out as Outcome.Err).error
        assertTrue(
            "expected BadBase64 or BadMac, got $err",
            err === PairedParseError.BadBase64 || err === PairedParseError.BadMac,
        )
    }
}
