package com.krypt.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.NamedParameterSpec

/**
 * Tests for [X25519KeyAgreement].
 *
 * Uses the RFC 7748 §6.1 test vector to verify shared-secret correctness, and
 * a JCE round-trip to verify symmetry of Diffie-Hellman.
 *
 * **JVM-only.** These tests run under `testDebugUnitTest` where the default
 * JDK provider (SunEC on JDK 11+) provides `XDH`. On Android API 29/30 the
 * XDH algorithm may be absent at runtime — see [X25519KeyAgreement] KDoc for
 * remediation options. An `assumeNoException` at setup skips the test rather
 * than failing in environments without XDH.
 */
class X25519Test {

    // RFC 7748 §6.1
    private val alicePriv = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val alicePub  = hexToBytes("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    private val bobPriv   = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val bobPub    = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dddc43f8e639b5bf7")
    private val sharedK   = hexToBytes("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

    @Before
    fun assumeXdhAvailable() {
        try {
            KeyFactory.getInstance("XDH")
        } catch (t: Throwable) {
            assumeNoException("XDH not available in this JCE provider", t)
        }
    }

    @Test
    fun rfc7748Section61Vector_aliceAgree() {
        // Reconstruct Alice's private key from raw scalar bytes (little-endian
        // per RFC 7748). Use `XECPrivateKeySpec` to inject the scalar.
        val alicePrivateKey = KeyFactory.getInstance("XDH")
            .generatePrivate(
                java.security.spec.XECPrivateKeySpec(
                    NamedParameterSpec("X25519"),
                    alicePriv
                )
            )

        val computed = X25519KeyAgreement.agree(alicePrivateKey, bobPub)
        assertArrayEquals(
            "Alice x Bob shared secret mismatch with RFC 7748 §6.1",
            sharedK,
            computed
        )
    }

    @Test
    fun rfc7748Section61Vector_bobAgree() {
        val bobPrivateKey = KeyFactory.getInstance("XDH")
            .generatePrivate(
                java.security.spec.XECPrivateKeySpec(
                    NamedParameterSpec("X25519"),
                    bobPriv
                )
            )

        val computed = X25519KeyAgreement.agree(bobPrivateKey, alicePub)
        assertArrayEquals(
            "Bob x Alice shared secret mismatch with RFC 7748 §6.1",
            sharedK,
            computed
        )
    }

    @Test
    fun roundTripAgreementIsSymmetric() {
        val aliceKp = X25519KeyAgreement.generateEphemeralKeyPair()
        val bobKp = X25519KeyAgreement.generateEphemeralKeyPair()

        val alicePublicBytes = X25519KeyAgreement.derivePublicKey(aliceKp)
        val bobPublicBytes = X25519KeyAgreement.derivePublicKey(bobKp)

        assertEquals(X25519KeyAgreement.PUBLIC_KEY_LEN, alicePublicBytes.size)
        assertEquals(X25519KeyAgreement.PUBLIC_KEY_LEN, bobPublicBytes.size)

        val aliceK = X25519KeyAgreement.agree(aliceKp.private, bobPublicBytes)
        val bobK   = X25519KeyAgreement.agree(bobKp.private,   alicePublicBytes)
        assertArrayEquals("A·B must equal B·A", aliceK, bobK)
        assertEquals(X25519KeyAgreement.SHARED_SECRET_LEN, aliceK.size)
    }

    @Test
    fun separateExchangesProduceDifferentSecrets() {
        val aliceKp1 = X25519KeyAgreement.generateEphemeralKeyPair()
        val bobKp1 = X25519KeyAgreement.generateEphemeralKeyPair()
        val s1 = X25519KeyAgreement.agree(aliceKp1.private, X25519KeyAgreement.derivePublicKey(bobKp1))

        val aliceKp2 = X25519KeyAgreement.generateEphemeralKeyPair()
        val bobKp2 = X25519KeyAgreement.generateEphemeralKeyPair()
        val s2 = X25519KeyAgreement.agree(aliceKp2.private, X25519KeyAgreement.derivePublicKey(bobKp2))

        assertEquals(
            "independent ephemeral exchanges MUST produce distinct shared secrets",
            false,
            s1.contentEquals(s2)
        )
    }

    @Test
    fun derivePublicKeyAlwaysReturns32Bytes() {
        repeat(16) {
            val kp = X25519KeyAgreement.generateEphemeralKeyPair()
            val pub = X25519KeyAgreement.derivePublicKey(kp)
            assertEquals("iteration $it", 32, pub.size)
        }
    }

    @Test
    fun publicKeyFromBytesRejectsWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            X25519KeyAgreement.publicKeyFromBytes(ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            X25519KeyAgreement.publicKeyFromBytes(ByteArray(33))
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
