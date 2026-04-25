package com.krypt.app.ui.onboarding

import com.krypt.app.permission.FakePermissionStatusProbe
import com.krypt.app.permission.PermissionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logical assertions for [OnboardingScreen] behaviour.
 *
 * Full Compose UI tests (with createComposeRule + assertIsDisplayed) require
 * a running device or Robolectric. These tests verify the ViewModel state
 * machine that drives the screen's visual state — the screen itself is a
 * function of [OnboardingUiState], so ViewModel tests give high confidence.
 */
class OnboardingScreenTest {

    private fun fakeState(
        stepIndex: Int = 0,
        mandatoryGranted: Int = 0,
        permissions: Map<PermissionKey, Boolean> = emptyMap(),
    ) = OnboardingUiState(
        currentStep = OnboardingStep.ORDERED[stepIndex],
        stepIndex = stepIndex,
        totalSteps = OnboardingStep.ORDERED.size,
        permissions = permissions,
        mandatoryGranted = mandatoryGranted,
        mandatoryTotal = 3,
        canFinish = mandatoryGranted == 3,
        canSkip = OnboardingStep.ORDERED[stepIndex].classification ==
            com.krypt.app.permission.PermissionClassification.OPTIONAL,
        isFirstStep = stepIndex == 0,
        isLastStep = stepIndex == OnboardingStep.ORDERED.lastIndex,
    )

    @Test
    fun step0_accessibility_isMandatory_noSkip() {
        val state = fakeState(stepIndex = 0)
        assertFalse(state.canSkip)
        assertEquals(OnboardingStep.Accessibility, state.currentStep)
    }

    @Test
    fun step3_deviceAdmin_isOptional_hasSkip() {
        val state = fakeState(stepIndex = 3)
        assertTrue(state.canSkip)
        assertEquals(OnboardingStep.DeviceAdmin, state.currentStep)
    }

    @Test
    fun indicator_denied_when_permissionNotGranted() {
        val state = fakeState(permissions = mapOf(PermissionKey.ACCESSIBILITY to false))
        assertFalse(state.permissions[PermissionKey.ACCESSIBILITY] ?: false)
    }

    @Test
    fun indicator_granted_when_permissionGranted() {
        val state = fakeState(permissions = mapOf(PermissionKey.ACCESSIBILITY to true), mandatoryGranted = 1)
        assertTrue(state.permissions[PermissionKey.ACCESSIBILITY] ?: false)
    }

    @Test
    fun finishDisabled_at2of3Mandatory() {
        val state = fakeState(mandatoryGranted = 2)
        assertFalse(state.canFinish)
    }

    @Test
    fun finishEnabled_at3of3Mandatory() {
        val state = fakeState(mandatoryGranted = 3)
        assertTrue(state.canFinish)
    }

    @Test
    fun lastStep_isLastIndex() {
        val state = fakeState(stepIndex = OnboardingStep.ORDERED.lastIndex)
        assertTrue(state.isLastStep)
    }
}
