package com.krypt.app.ui.onboarding

import com.krypt.app.R
import com.krypt.app.permission.PermissionClassification
import com.krypt.app.permission.PermissionKey

/**
 * Canonical descriptor for each onboarding permission step.
 *
 * [ORDERED] places Mandatory steps first so the mandatory progress bar fills
 * as the user moves through the natural onboarding sequence.
 */
sealed class OnboardingStep(
    val key: PermissionKey,
    val classification: PermissionClassification,
    val titleRes: Int,
    val bodyRes: Int,
    val grantRes: Int,
) {
    data object Accessibility : OnboardingStep(
        key = PermissionKey.ACCESSIBILITY,
        classification = PermissionClassification.MANDATORY,
        titleRes = R.string.onboarding_step1_title,
        bodyRes = R.string.onboarding_step1_body,
        grantRes = R.string.onboarding_step1_grant,
    )

    data object Overlay : OnboardingStep(
        key = PermissionKey.OVERLAY,
        classification = PermissionClassification.MANDATORY,
        titleRes = R.string.onboarding_step2_title,
        bodyRes = R.string.onboarding_step2_body,
        grantRes = R.string.onboarding_step2_grant,
    )

    data object Battery : OnboardingStep(
        key = PermissionKey.BATTERY,
        classification = PermissionClassification.MANDATORY,
        titleRes = R.string.onboarding_step5_title,
        bodyRes = R.string.onboarding_step5_body,
        grantRes = R.string.onboarding_step5_grant,
    )

    data object DeviceAdmin : OnboardingStep(
        key = PermissionKey.DEVICE_ADMIN,
        classification = PermissionClassification.OPTIONAL,
        titleRes = R.string.onboarding_step3_title,
        bodyRes = R.string.onboarding_step3_body,
        grantRes = R.string.onboarding_step3_grant,
    )

    data object Notifications : OnboardingStep(
        key = PermissionKey.NOTIFICATIONS,
        classification = PermissionClassification.OPTIONAL,
        titleRes = R.string.onboarding_step4_title,
        bodyRes = R.string.onboarding_step4_body,
        grantRes = R.string.onboarding_step4_grant,
    )

    companion object {
        /** Canonical display order: Mandatory steps first (FR-022). */
        val ORDERED: List<OnboardingStep> = listOf(
            Accessibility, Overlay, Battery, DeviceAdmin, Notifications,
        )
    }
}
