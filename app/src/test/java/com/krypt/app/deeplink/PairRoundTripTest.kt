package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.X25519KeyAgreement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.util.UUID

/**
 * End-to-end pairing round-trip on a single JVM:
 *   Subject builds `krypt://pair` -> Guardian parses + derives K_pair +
 *   builds `krypt://paired` -> Subject verifies + derives the same K_pair.
 */
class PairRoundTripTest {

    @Before
    fun assumeXdhAvailable() {
        try { KeyFactory.getInstance("XDH") } catch (t: Throwable) {
            assumeNoException("XDH not available in this JCE provider", t)
        }
    }

    private val clockSubject = FixedClockP(nowMs = 1_700_000_000_000L)
    private val clockGuardian = FixedClockP(nowMs = 1_700_000_001_000L)

    private val pairBuilder = PairRequestBuilder(clockSubject)
    private val pairParser = PairRequestParser()
    private val pairedBuilder = PairedReplyBuilder(clockGuardian)
    private val pairedVerifier = PairedReplyVerifier()

    @Test
    fun fullPairingHandshakeDerivesIdenticalKPairOnBothSides() {
        // Subject side.
        val subjectKp = X25519KeyAgreement.generateEphemeralKeyPair()
        val subjectId = UUID.fromString("12345678-1234-4abc-8def-123456789abc")
        val (pairUrl, request) = pairBuilder.build(
            subjectId = subjectId,
            subjectDisplayName = "Alice's Pixel",
            ephKeyPair = subjectKp,
        )

        // Guardian side: parse + generate own keypair + compute K_pair.
        val parsedRequest = (pairParser.parse(pairUrl, clockGuardian.nowSeconds()) as Outcome.Ok).value
        assertEquals(request, parsedRequest)

        val guardianKp = X25519KeyAgreement.generateEphemeralKeyPair()
        val (pairedUrl, guardianKPair) = pairedBuilder.buildWithFreshAgreement(
            incoming = parsedRequest,
            guardianDisplayName = "Dad's Phone",
            guardianEphKeyPair = guardianKp,
            guardianPubSalt = ByteArray(32) { (it + 7).toByte() },
            guardianKdfIterations = 400_000,
        )

        // Subject side: verify + derive K_pair.
        val verifyOutcome = pairedVerifier.parseAndVerify(
            url = pairedUrl,
            nowSeconds = clockSubject.nowSeconds() + 10,
            myEphPrivate = subjectKp.private,
            myExpectedSubjectId = subjectId,
        )
        val (reply, subjectKPair) = (verifyOutcome as Outcome.Ok).value

        assertEquals("Dad's Phone", reply.guardianDisplayName)
        assertEquals(32, subjectKPair.size)
        assertArrayEquals(
            "Subject and Guardian must derive identical K_pair",
            guardianKPair,
            subjectKPair,
        )
    }

    @Test
    fun pinRotationWithExistingKPairProducesFreshPubSalt() {
        val subjectKp = X25519KeyAgreement.generateEphemeralKeyPair()
        val subjectId = UUID.fromString("22345678-1234-4abc-8def-123456789abc")
        val (pairUrl, _) = pairBuilder.build(subjectId, "Alice", subjectKp)
        val parsed = (pairParser.parse(pairUrl, clockGuardian.nowSeconds()) as Outcome.Ok).value

        val guardianKp = X25519KeyAgreement.generateEphemeralKeyPair()
        val (_, kPair) = pairedBuilder.buildWithFreshAgreement(
            incoming = parsed,
            guardianDisplayName = "G",
            guardianEphKeyPair = guardianKp,
            guardianPubSalt = ByteArray(32) { 0x11 },
            guardianKdfIterations = 400_000,
        )

        // Now simulate PIN rotation: same K_pair, fresh pubSalt.
        val guardianEphPub = X25519KeyAgreement.derivePublicKey(guardianKp.public)
        val rotatedUrl = pairedBuilder.buildWithExistingKPair(
            subjectId = subjectId,
            kPair = kPair,
            guardianDisplayName = "G",
            guardianPubSalt = ByteArray(32) { 0x22 },
            guardianEphPub = guardianEphPub,
            guardianKdfIterations = 400_000,
        )

        val rotatedOutcome = pairedVerifier.parseAndVerify(
            url = rotatedUrl,
            nowSeconds = clockSubject.nowSeconds() + 10,
            myEphPrivate = subjectKp.private,
            myExpectedSubjectId = subjectId,
        )
        val (rotatedReply, rotatedKPair) = (rotatedOutcome as Outcome.Ok).value
        assertArrayEquals(
            "PIN rotation MUST NOT change K_pair",
            kPair, rotatedKPair,
        )
        assertEquals(0x22.toByte(), rotatedReply.guardianPubSalt[0])
    }
}

internal class FixedClockP(var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}
