package com.krypt.app.ui.onboarding

import com.krypt.app.R
import com.krypt.app.permission.PermissionClassification
import com.krypt.app.permission.PermissionKey

/**
 * Canonical descriptor for each onboarding permission step: one row of the
 * onboarding checklist.
 *
 * [ORDERED] places Mandatory steps first, so the checklist's Required group
 * comes before its Optional one.
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
        titleRes = R.string.onboarding_accessibility_title,
        bodyRes = R.string.onboarding_accessibility_line,
        grantRes = R.string.permission_allow_in_settings,
    )

    data object Overlay : OnboardingStep(
        key = PermissionKey.OVERLAY,
        classification = PermissionClassification.MANDATORY,
        titleRes = R.string.onboarding_overlay_title,
        bodyRes = R.string.onboarding_overlay_line,
        grantRes = R.string.permission_allow_in_settings,
    )

    data object Battery : OnboardingStep(
        key = PermissionKey.BATTERY,
        classification = PermissionClassification.OPTIONAL,
        titleRes = R.string.onboarding_battery_title,
        bodyRes = R.string.onboarding_battery_line,
        grantRes = R.string.permission_allow_in_settings,
    )

    data object DeviceAdmin : OnboardingStep(
        key = PermissionKey.DEVICE_ADMIN,
        classification = PermissionClassification.OPTIONAL,
        titleRes = R.string.onboarding_admin_title,
        bodyRes = R.string.onboarding_admin_line,
        grantRes = R.string.permission_allow_in_settings,
    )

    data object Notifications : OnboardingStep(
        key = PermissionKey.NOTIFICATIONS,
        classification = PermissionClassification.OPTIONAL,
        titleRes = R.string.onboarding_notifications_title,
        bodyRes = R.string.onboarding_notifications_line,
        grantRes = R.string.permission_allow,
    )

    companion object {
        /** Canonical display order: Mandatory steps first (FR-022). */
        val ORDERED: List<OnboardingStep> = listOf(
            Accessibility, Overlay, Battery, DeviceAdmin, Notifications,
        )
    }
}
