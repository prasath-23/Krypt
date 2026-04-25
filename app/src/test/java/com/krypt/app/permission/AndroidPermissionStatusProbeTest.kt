package com.krypt.app.permission

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AndroidPermissionStatusProbe] using MockK to simulate
 * each OS-level permission grant / denial without a device or Robolectric.
 */
class AndroidPermissionStatusProbeTest {

    private val ctx = mockk<Context>(relaxed = true)
    private val adminComponent = mockk<ComponentName>()
    private val a11yManager = mockk<AccessibilityManager>(relaxed = true)
    private val dpm = mockk<DevicePolicyManager>(relaxed = true)
    private val pm = mockk<PowerManager>(relaxed = true)
    private val nmCompat = mockk<NotificationManagerCompat>(relaxed = true)

    private lateinit var probe: AndroidPermissionStatusProbe

    @Before
    fun setUp() {
        mockkStatic(Settings::class, NotificationManagerCompat::class)
        every { ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) } returns a11yManager
        every { ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) } returns dpm
        every { ctx.getSystemService(Context.POWER_SERVICE) } returns pm
        every { ctx.packageName } returns "com.krypt.app"
        every { NotificationManagerCompat.from(ctx) } returns nmCompat

        probe = AndroidPermissionStatusProbe(ctx, adminComponent)
    }

    @After
    fun tearDown() = unmockkAll()

    // ─────────────────────────── ACCESSIBILITY ───────────────────────────────

    @Test
    fun accessibility_returnsFalse_whenNoMatchingServiceEnabled() {
        every { a11yManager.getEnabledAccessibilityServiceList(any()) } returns emptyList()
        assertFalse(probe.statusOf(PermissionKey.ACCESSIBILITY))
    }

    @Test
    fun accessibility_returnsTrue_whenKryptServiceListed() {
        val info = mockk<AccessibilityServiceInfo>(relaxed = true)
        every { info.id } returns "com.krypt.app/.service.AppLockerAccessibilityService"
        every { a11yManager.getEnabledAccessibilityServiceList(any()) } returns listOf(info)

        assertTrue(probe.statusOf(PermissionKey.ACCESSIBILITY))
    }

    // ──────────────────────────────── OVERLAY ────────────────────────────────

    @Test
    fun overlay_returnsFalse_whenCannotDrawOverlays() {
        every { Settings.canDrawOverlays(ctx) } returns false
        assertFalse(probe.statusOf(PermissionKey.OVERLAY))
    }

    @Test
    fun overlay_returnsTrue_whenCanDrawOverlays() {
        every { Settings.canDrawOverlays(ctx) } returns true
        assertTrue(probe.statusOf(PermissionKey.OVERLAY))
    }

    // ──────────────────────────────── BATTERY ────────────────────────────────

    @Test
    fun battery_returnsFalse_whenNotIgnoringOptimisations() {
        every { pm.isIgnoringBatteryOptimizations("com.krypt.app") } returns false
        assertFalse(probe.statusOf(PermissionKey.BATTERY))
    }

    @Test
    fun battery_returnsTrue_whenIgnoringOptimisations() {
        every { pm.isIgnoringBatteryOptimizations("com.krypt.app") } returns true
        assertTrue(probe.statusOf(PermissionKey.BATTERY))
    }

    @Test
    fun battery_returnsFalse_whenSecurityExceptionThrown() {
        every { pm.isIgnoringBatteryOptimizations(any()) } throws SecurityException("OEM block")
        assertFalse(probe.statusOf(PermissionKey.BATTERY))
    }

    // ──────────────────────────── DEVICE ADMIN ───────────────────────────────

    @Test
    fun deviceAdmin_returnsFalse_whenNotActive() {
        every { dpm.isAdminActive(adminComponent) } returns false
        assertFalse(probe.statusOf(PermissionKey.DEVICE_ADMIN))
    }

    @Test
    fun deviceAdmin_returnsTrue_whenActive() {
        every { dpm.isAdminActive(adminComponent) } returns true
        assertTrue(probe.statusOf(PermissionKey.DEVICE_ADMIN))
    }

    // ─────────────────────────── NOTIFICATIONS ───────────────────────────────

    @Test
    fun notifications_returnsFalse_whenDisabled() {
        every { nmCompat.areNotificationsEnabled() } returns false
        assertFalse(probe.statusOf(PermissionKey.NOTIFICATIONS))
    }

    @Test
    fun notifications_returnsTrue_whenEnabled() {
        every { nmCompat.areNotificationsEnabled() } returns true
        assertTrue(probe.statusOf(PermissionKey.NOTIFICATIONS))
    }

    // ─────────────────────────── statusAll ───────────────────────────────────

    @Test
    fun statusAll_returnsMapWithAllFiveKeys() {
        every { a11yManager.getEnabledAccessibilityServiceList(any()) } returns emptyList()
        every { Settings.canDrawOverlays(ctx) } returns false
        every { pm.isIgnoringBatteryOptimizations(any()) } returns false
        every { dpm.isAdminActive(any()) } returns false
        every { nmCompat.areNotificationsEnabled() } returns false

        val all = probe.statusAll()
        assertTrue(all.containsKey(PermissionKey.ACCESSIBILITY))
        assertTrue(all.containsKey(PermissionKey.OVERLAY))
        assertTrue(all.containsKey(PermissionKey.BATTERY))
        assertTrue(all.containsKey(PermissionKey.DEVICE_ADMIN))
        assertTrue(all.containsKey(PermissionKey.NOTIFICATIONS))
    }
}
