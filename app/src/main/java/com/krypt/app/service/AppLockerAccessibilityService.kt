package com.krypt.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.krypt.app.R
import com.krypt.app.data.LockState
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.deeplink.UnlockRequestBuilder
import com.krypt.app.di.ApplicationScope
import com.krypt.app.security.MasterKeyStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Inject lateinit var unlockRequestBuilder: UnlockRequestBuilder
    @Inject lateinit var masterKeyStore: MasterKeyStore
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
        appScope.launch {
            try {
                val salt = masterKeyStore.loadSalt()
                val pinProof = masterKeyStore.loadPinProof()
                if (salt == null || pinProof == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@AppLockerAccessibilityService,
                            "Set up a Guardian PIN first.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }

                val displayName = displayNameFor(pkg)
                val (url, _) = unlockRequestBuilder.build(
                    setupSalt = salt,
                    pinProof = pinProof,
                    targetPackage = pkg,
                )

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                    putExtra(Intent.EXTRA_SUBJECT, "Krypt unlock request: $displayName")
                }
                val chooser = Intent.createChooser(
                    sendIntent,
                    getString(R.string.home_share_chooser_title),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

                withContext(Dispatchers.Main) {
                    // Hide the overlay so the chooser isn't visually buried under it.
                    overlayManager.hide()
                    startActivity(chooser)
                }
                Log.i(TAG, "share chooser launched for pkg=$pkg")
            } catch (t: Throwable) {
                Log.e(TAG, "ask-guardian failed for pkg=$pkg", t)
            }
        }
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
        pkg in SYSTEM_UI_PKGS

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
