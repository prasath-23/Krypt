package com.krypt.app.subject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lightweight JVM-only checks for [UnlockSuccessEffect] constants
 * (FR-021). The Android-framework behaviour (Toast, Vibrator,
 * AccessibilityManager, ObjectAnimator) is exercised via the instrumented
 * e2e tests in WP23.
 */
class UnlockSuccessEffectConstantsTest {

    @Test
    fun flashDurationIsAtLeast500ms() {
        // FR-021 mandates a >=500 ms green flash.
        assertTrue(
            "FR-021 requires >=500 ms flash; got ${UnlockSuccessEffect.FLASH_DURATION_MS}",
            UnlockSuccessEffect.FLASH_DURATION_MS >= 500L,
        )
    }

    @Test
    fun hapticIsBrief() {
        // Non-functional requirement: haptic should be a single short pulse,
        // not a long buzz (would be intrusive on a family-safety device).
        assertTrue(
            "haptic should be <= 100 ms; got ${UnlockSuccessEffect.HAPTIC_MS}",
            UnlockSuccessEffect.HAPTIC_MS in 20L..100L,
        )
    }
}
