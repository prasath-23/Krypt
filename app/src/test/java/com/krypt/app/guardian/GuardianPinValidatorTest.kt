package com.krypt.app.guardian

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.security.MasterKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Tests for [GuardianPinValidator] (WP21 T110).
 *
 * Runs a REAL PBKDF2-HMAC-SHA-256 derive at the FR-008 floor (300k iters)
 * end-to-end, which on CI-class hardware takes ~150-400 ms per test. A
 * couple of tests is enough to prove behaviour; we don't need 100 random
 * cases here because the crypto primitives themselves are covered by
 * HmacProviderTest and KdfProviderTest.
 */
class GuardianPinValidatorTest {

    private val kdf = KdfProvider()
    private val hmac = HmacProvider()
    private val validator = GuardianPinValidator(kdf, hmac)

    private val salt = ByteArray(UnlockRequest.SALT_BYTES) { it.toByte() }
    private val iterations = KdfProvider.MIN_ITERATIONS

    private fun buildRequestFor(pin: String): UnlockRequest {
        val masterKey = kdf.derive(
            pin.toCharArray(),
            salt,
            iterations,
            MasterKeyStore.MASTER_KEY_BYTES,
        )
        val proof = hmac.sha256(
            masterKey,
            MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
        )
        masterKey.fill(0)
        return UnlockRequest(
            requestId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
            targetPackage = "com.example.target",
            salt = salt,
            pinProof = proof,
            issuedAt = 1_700_000_000L,
            ttlSeconds = 300L,
        )
    }

    @Test
    fun correctPinYieldsMatchingMasterKey() = runTest {
        val request = buildRequestFor("1234")
        val result = validator.validate(
            pin = "1234".toCharArray(),
            request = request,
            iterations = iterations,
        )
        assertTrue("expected Ok, got $result", result is Outcome.Ok)
        val masterKey = (result as Outcome.Ok).value
        assertEquals(MasterKeyStore.MASTER_KEY_BYTES, masterKey.size)

        // And that masterKey is indeed the one that produces request.pinProof.
        val recomputedProof = hmac.sha256(
            masterKey,
            MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
        )
        assertArrayEquals(request.pinProof, recomputedProof)
    }

    @Test
    fun wrongPinYieldsWrongPinError() = runTest {
        val request = buildRequestFor("1234")
        val result = validator.validate(
            pin = "0000".toCharArray(),
            request = request,
            iterations = iterations,
        )
        assertSame(
            GuardianPinValidator.ValidationError.WrongPin,
            (result as Outcome.Err).error,
        )
    }

    @Test
    fun tamperedPinProofYieldsWrongPin() = runTest {
        val request = buildRequestFor("1234")
        val tamperedProof = request.pinProof.copyOf().apply {
            this[0] = (this[0].toInt() xor 0x01).toByte()
        }
        val tampered = request.copy(pinProof = tamperedProof)
        val result = validator.validate(
            pin = "1234".toCharArray(),
            request = tampered,
            iterations = iterations,
        )
        assertSame(
            GuardianPinValidator.ValidationError.WrongPin,
            (result as Outcome.Err).error,
        )
    }

    @Test
    fun pinCharArrayIsZeroedAfterValidate() = runTest {
        val request = buildRequestFor("1234")
        val pin = "1234".toCharArray()
        validator.validate(pin = pin, request = request, iterations = iterations)
        assertArrayEquals(CharArray(4) { ' ' }, pin)
    }

    @Test
    fun pinCharArrayIsZeroedAfterWrongPin() = runTest {
        val request = buildRequestFor("1234")
        val pin = "0000".toCharArray()
        validator.validate(pin = pin, request = request, iterations = iterations)
        assertArrayEquals(CharArray(4) { ' ' }, pin)
    }

    @Test
    fun rejectsBelowFloorIterations() = runTest {
        val request = buildRequestFor("1234")
        try {
            validator.validate(
                pin = "1234".toCharArray(),
                request = request,
                iterations = 1000,
            )
            throw AssertionError("should have thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsEmptyPin() = runTest {
        val request = buildRequestFor("1234")
        try {
            validator.validate(
                pin = CharArray(0),
                request = request,
                iterations = iterations,
            )
            throw AssertionError("should have thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
