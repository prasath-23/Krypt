package com.krypt.app.service

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import com.krypt.app.data.LockState
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FR-001 interception core. Observes TYPE_WINDOW_STATE_CHANGED, decides if
 * the foregrounded package is locked, and shows / hides the pre-inflated
 * overlay. FR-011 self-whitelist: GuardianActivity is never overlaid.
 */
@AndroidEntryPoint
class AppLockerAccessibilityService : AccessibilityService() {

    @Inject lateinit var lockedAppsRepo: LockedAppsRepository
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var overlayManager: OverlayManager
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    @Volatile private var lockedPackagesCache: Set<String> = emptySet()
    private var lockedAppsFlow: StateFlow<Set<String>>? = null
    private var flowJob: Job? = null

    private val nameCache = LruCache<String, String>(64)
    private val iconCache = LruCache<String, Drawable>(64)

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager.preinflate()
        overlayManager.bindAskGuardian(::onAskGuardianClicked)
        subscribeToLockedAppsFlow()
        Log.i(TAG, "connected")
    }

    private fun subscribeToLockedAppsFlow() {
        val flow = lockedAppsRepo.observeLockedApps()
            .map { list -> list.filter { it.lockState == LockState.LOCKED }.map { it.packageName }.toSet() }
            .stateIn(appScope, SharingStarted.Eagerly, emptySet())
        lockedAppsFlow = flow
        flowJob = appScope.launch {
            flow.collect { set -> lockedPackagesCache = set }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = ev.packageName?.toString() ?: return
        val className = ev.className?.toString()

        if (pkg == packageName) return

        // FR-011: whitelist our own Guardian Popup.
        if (className == GUARDIAN_ACTIVITY_CLASS) {
            overlayManager.hide()
            return
        }

        if (isSystemUiPackage(pkg)) {
            overlayManager.hide()
            return
        }

        if (sessionStore.isUnlockedNow(pkg)) {
            overlayManager.hide()
            return
        }

        if (pkg in lockedPackagesCache) {
            val t0 = SystemClock.elapsedRealtime()
            overlayManager.show(pkg, displayNameFor(pkg), iconFor(pkg))
            val dt = SystemClock.elapsedRealtime() - t0
            Log.d(TAG_LATENCY, "pkg=$pkg show_ms=$dt")
        } else {
            overlayManager.hide()
        }
    }

    override fun onInterrupt() {
        // no-op; required by AccessibilityService.
    }

    override fun onDestroy() {
        flowJob?.cancel()
        super.onDestroy()
    }

    private fun onAskGuardianClicked(pkg: String) {
        // WP14 wires the real Share-sheet emit for krypt://request. Here we
        // just log; the OverlayManager callback contract is stable.
        Log.i(TAG, "ask-guardian requested for pkg=$pkg (wired by WP14)")
    }

    private fun displayNameFor(pkg: String): String {
        nameCache.get(pkg)?.let { return it }
        val name = try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
        nameCache.put(pkg, name)
        return name
    }

    private fun iconFor(pkg: String): Drawable? {
        iconCache.get(pkg)?.let { return it }
        val icon = try {
            packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (icon != null) iconCache.put(pkg, icon)
        return icon
    }

    private fun isSystemUiPackage(pkg: String): Boolean =
        pkg in SYSTEM_UI_PKGS || pkg.startsWith("com.android.")

    companion object {
        const val GUARDIAN_ACTIVITY_CLASS: String = "com.krypt.app.ui.guardian.GuardianActivity"
        private const val TAG = "KryptA11y"
        const val TAG_LATENCY = "KryptA11yLatency"
        private val SYSTEM_UI_PKGS: Set<String> = setOf(
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.googlequicksearchbox",
        )
    }
}
