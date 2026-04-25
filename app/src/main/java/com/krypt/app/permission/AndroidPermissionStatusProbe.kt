package com.krypt.app.permission

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [PermissionStatusProbe] that interrogates the
 * Android OS for each [PermissionKey]. All calls are synchronous and cheap
 * enough to run on the main thread during onResume.
 */
@Singleton
class AndroidPermissionStatusProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceAdminComponent: ComponentName,
) : PermissionStatusProbe {

    private val a11yManager by lazy {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }
    private val dpm by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val pm by lazy { context.packageManager }

    override fun statusOf(key: PermissionKey): Boolean = when (key) {
        PermissionKey.ACCESSIBILITY -> isAccessibilityEnabled()
        PermissionKey.OVERLAY -> Settings.canDrawOverlays(context)
        PermissionKey.BATTERY -> isBatteryOptimisationIgnored()
        PermissionKey.DEVICE_ADMIN -> dpm.isAdminActive(deviceAdminComponent)
        PermissionKey.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val pkg = context.packageName
        return a11yManager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.id.startsWith(pkg) }
    }

    private fun isBatteryOptimisationIgnored(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: SecurityException) {
        // Some OEMs throw on fresh install before the user interacts with battery settings.
        false
    }
}
