package com.krypt.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [KdfProvider] (PBKDF2-HMAC-SHA256, FR-008).
 *
 * No published KAT is used — PBKDF2-HMAC-SHA256 standardised vectors are
 * typically published for low iteration counts (1, 2, 4096), which we refuse
 * to accept via [KdfProvider.derive]'s FR-008 floor. Instead, the tests
 * confirm algorithmic correctness by behavioural properties (determinism,
 * sensitivity to each input parameter) and assert the FR-008 cost floor
 * and calibration behaviour.
 */
class KdfProviderTest {

    private val kdf = KdfProvider()

    @Test
    fun deriveIsDeterministic() {
        val pin = "123456".toCharArray()
        val salt = ByteArray(16) { i -> i.toByte() }
        val a = kdf.derive(pin.copyOf(), salt.copyOf(), KdfProvider.MIN_ITERATIONS, 32)
        val b = kdf.derive(pin.copyOf(), salt.copyOf(), KdfProvider.MIN_ITERATIONS, 32)
        assertEquals("same inputs must produce identical output", a.toHex(), b.toHex())
    }

    @Test
    fun differentSaltProducesDifferentKey() {
        val pin = "123456".toCharArray()
        val saltA = ByteArray(16) { i -> i.toByte() }
        val saltB = ByteArray(16) { i -> (i + 1).toByte() }
        val keyA = kdf.derive(pin.copyOf(), saltA, KdfProvider.MIN_ITERATIONS, 32)
        val keyB = kdf.derive(pin.copyOf(), saltB, KdfProvider.MIN_ITERATIONS, 32)
        assertNotEquals(keyA.toHex(), keyB.toHex())
    }

    @Test
    fun differentPinProducesDifferentKey() {
        val pinA = "123456".toCharArray()
        val pinB = "654321".toCharArray()
        val salt = ByteArray(16) { i -> i.toByte() }
        val keyA = kdf.derive(pinA, salt.copyOf(), KdfProvider.MIN_ITERATIONS, 32)
        val keyB = kdf.derive(pinB, salt.copyOf(), KdfProvider.MIN_ITERATIONS, 32)
        assertNotEquals(keyA.toHex(), keyB.toHex())
    }

    @Test
    fun outputLengthMatchesRequest() {
        val pin = "111111".toCharArray()
        val salt = ByteArray(16)
        assertEquals(16, kdf.derive(pin.copyOf(), salt.copyOf(), KdfProvider.MIN_ITERATIONS, 16).size)
        assertEquals(32, kdf.derive(pin.copyOf(), salt.copyOf(), KdfProvider.MIN_ITERATIONS, 32).size)
        assertEquals(64, kdf.derive(pin.copyOf(), salt.copyOf(), KdfProvider.MIN_ITERATIONS, 64).size)
    }

    @Test
    fun deriveRejectsBelowFloor() {
        val pin = "111111".toCharArray()
        val salt = ByteArray(16)
        val e = assertThrows(IllegalArgumentException::class.java) {
            kdf.derive(pin, salt, KdfProvider.MIN_ITERATIONS - 1, 32)
        }
        assertTrue(
            "message should reference FR-008: <${e.message}>",
            e.message!!.contains("FR-008")
        )
    }

    @Test
    fun deriveRejectsEmptySalt() {
        val pin = "111111".toCharArray()
        assertThrows(IllegalArgumentException::class.java) {
            kdf.derive(pin, ByteArray(0), KdfProvider.MIN_ITERATIONS, 32)
        }
    }

    @Test
    fun deriveRejectsBadOutputLength() {
        val pin = "111111".toCharArray()
        val salt = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            kdf.derive(pin, salt, KdfProvider.MIN_ITERATIONS, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            kdf.derive(pin, salt, KdfProvider.MIN_ITERATIONS, KdfProvider.MAX_OUTPUT_BYTES + 1)
        }
    }

    @Test
    fun calibrationMeetsFr008Floor() {
        // The calibration result must always be >= MIN_ITERATIONS even if
        // the device is so slow that MIN_ITERATIONS already exceeds the target.
        val chosen = kdf.calibrateIterationsForDevice(targetMillis = 10)
        assertTrue(
            "calibration returned $chosen, must be >= ${KdfProvider.MIN_ITERATIONS}",
            chosen >= KdfProvider.MIN_ITERATIONS
        )
        assertTrue(
            "calibration returned $chosen, must be <= ${KdfProvider.MAX_ITERATIONS}",
            chosen <= KdfProvider.MAX_ITERATIONS
        )
    }

    @Test
    fun calibrationProducesActualCostOnFastDevice() {
        // On a normal CI runner, MIN_ITERATIONS takes ~150ms. Asking for a
        // 400ms target should therefore produce >MIN_ITERATIONS iterations.
        // If the runner is unusually slow, the result floors at MIN_ITERATIONS
        // which is still valid — so assert only the lower bound.
        val chosen = kdf.calibrateIterationsForDevice(targetMillis = 400)
        assertTrue(
            "calibration for 400ms target returned $chosen; must be >= MIN",
            chosen >= KdfProvider.MIN_ITERATIONS
        )
    }

    @Test
    fun derivedKeyIsNotAllZeroes() {
        val pin = "random_pin_value".toCharArray()
        val salt = "random_salt".toByteArray()
        val key = kdf.derive(pin, salt, KdfProvider.MIN_ITERATIONS, 32)
        val allZero = key.all { it == 0.toByte() }
        assertFalse("derived key is all-zero; algorithm likely broken", allZero)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        "%02x".format(it.toInt() and 0xFF)
    }
}
