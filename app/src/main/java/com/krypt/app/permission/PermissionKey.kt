package com.krypt.app.permission

/**
 * Canonical identifier for each OS-level permission Krypt cares about.
 *
 * - ACCESSIBILITY -> AccessibilityService enabled for this package
 * - OVERLAY       -> android.permission.SYSTEM_ALERT_WINDOW (Settings.canDrawOverlays)
 * - BATTERY       -> android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 * - DEVICE_ADMIN  -> DevicePolicyManager.isAdminActive
 * - NOTIFICATIONS -> android.permission.POST_NOTIFICATIONS (API 33+)
 */
enum class PermissionKey {
    ACCESSIBILITY,
    OVERLAY,
    BATTERY,
    DEVICE_ADMIN,
    NOTIFICATIONS;

    companion object {
        /**
         * The permissions that MUST be granted before setup is complete (FR-022).
         * BATTERY is optional since Amendment 5: Doze doesn't stop the
         * accessibility service, so only phones whose maker stops background
         * apps need it.
         */
        val MANDATORY: Set<PermissionKey> = setOf(ACCESSIBILITY, OVERLAY)
    }
}

/** Classification of a [PermissionKey] as blocking or skippable (FR-022). */
enum class PermissionClassification {
    MANDATORY,
    OPTIONAL,
}

/** Returns the [PermissionClassification] of this key. */
fun PermissionKey.classification(): PermissionClassification =
    if (this in PermissionKey.MANDATORY) PermissionClassification.MANDATORY
    else PermissionClassification.OPTIONAL
