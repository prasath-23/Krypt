package com.krypt.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.krypt.app.common.TrustedDayClock
import com.krypt.app.data.AccessDecision
import com.krypt.app.data.AccessPolicy
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.daily.DailyAllowanceMeter
import com.krypt.app.di.ApplicationScope
import com.krypt.app.receiver.NewInstallLocker
import com.krypt.app.ui.lock.LockScreenActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FR-001 interception core. Observes TYPE_WINDOW_STATE_CHANGED and, when a
 * locked package reaches the foreground and nothing allows it - no active
 * grant, no every-day time left ([AccessPolicy]) - replaces it with
 * [LockScreenActivity] (Amendment 2: locked apps are blocked, not covered by
 * an overlay). Krypt's own windows - the lock screen itself and
 * the Guardian popup (FR-011) - are never blocked.
 *
 * Also hosts the parts of Krypt that must keep running: the new-install
 * auto-lock (FR-003, via [NewInstallLocker]), grant expiry for an app that
 * is still open when its grant ends (FR-013, via [GrantExpiryWatcher]), and
 * counting every-day time (Amendment 3, via [DailyAllowanceMeter], fed by
 * window changes and [DeviceStateWatcher]).
 */
@AndroidEntryPoint
class AppLockerAccessibilityService : AccessibilityService() {

    @Inject lateinit var lockedAppsRepo: LockedAppsRepository
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var newInstallLocker: NewInstallLocker
    @Inject lateinit var dailyMeter: DailyAllowanceMeter
    @Inject lateinit var dayClock: TrustedDayClock
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    @Volatile private var lockedPackagesCache: Set<String> = emptySet()

    /** The app the user is in; keyboard and notification-shade windows don't change it. */
    private var foregroundPackage: String? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var deviceState: DeviceStateWatcher? = null

    private var watchingInstalls = false
    private val packageAddedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            launchSafely("auto-lock $pkg") { newInstallLocker.onPackageAdded(pkg, replacing) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        subscribeToLockedAppsFlow()
        GrantExpiryWatcher(serviceScope, sessionStore, ::onGrantExpired).start()
        dailyMeter.attach(serviceScope, ::onDailyAccessEnded)
        deviceState = DeviceStateWatcher(this, dailyMeter, dayClock) {
            foregroundPackage?.let(::blockIfNeeded)
        }.also { it.start() }
        watchNewInstalls()
        KryptWatchdogService.start(this)
        Log.i(TAG, "connected")
    }

    private fun subscribeToLockedAppsFlow() {
        serviceScope.launch {
            // With the every-day rules loaded first, an app with daily time left
            // is never blocked in the moment after Krypt starts.
            dailyMeter.loaded.first { it }
            lockedAppsRepo.allLockedFlow().collect { set ->
                lockedPackagesCache = set
            }
        }
    }

    private fun watchNewInstalls() {
        ContextCompat.registerReceiver(
            this,
            packageAddedReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        watchingInstalls = true
        launchSafely("new-install catch-up") { newInstallLocker.catchUp() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = ev.packageName?.toString() ?: return
        if (!isTransientWindow(pkg, ev.className)) {
            foregroundPackage = pkg
            // Also for Krypt's own windows, so they stop an app's daily time.
            dailyMeter.onForeground(pkg, locked = pkg in lockedPackagesCache)
        }

        blockIfNeeded(pkg)
    }

    override fun onInterrupt() {
        // no-op; required by AccessibilityService.
    }

    override fun onDestroy() {
        deviceState?.stop()
        dailyMeter.detach()
        if (watchingInstalls) {
            try {
                unregisterReceiver(packageAddedReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    /** A grant ran out: if its app is still open, block it now - unless its daily time takes over. */
    private fun onGrantExpired(pkg: String) {
        dailyMeter.reevaluate()
        if (pkg == foregroundPackage) blockIfNeeded(pkg)
    }

    /** The app's every-day time ran out, or its rule ended, while it is open. */
    private fun onDailyAccessEnded(pkg: String) {
        if (pkg == foregroundPackage) blockIfNeeded(pkg)
    }

    /** Block [pkg] if Krypt locks it and nothing allows it right now ([AccessPolicy]). */
    private fun blockIfNeeded(pkg: String) {
        if (pkg == packageName || pkg !in lockedPackagesCache) return
        val decision = AccessPolicy.decide(
            isLocked = true,
            hasGrant = sessionStore.isUnlockedNow(pkg),
            daily = dailyMeter.access(pkg),
        )
        if (decision is AccessDecision.Block && !isNeverBlocked(pkg)) blockLaunch(pkg)
    }

    /**
     * Close the locked app and show the lock screen. The app is sent behind
     * the home screen first, so it is never directly under the lock screen:
     * if Krypt's process dies (a crash, an update), Android removes the lock
     * screen and the home screen shows, not the app. Leaving the lock screen
     * also goes to the home screen, and bringing the app back (launcher,
     * Recents, a notification) fires another event.
     */
    private fun blockLaunch(pkg: String) {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Started together with no transition animation, so the app is
        // hidden immediately and the home screen never flashes up.
        val noAnimation = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        try {
            startActivities(arrayOf(home, LockScreenActivity.intentFor(this, pkg)), noAnimation)
            Log.i(TAG, "blocked pkg=$pkg")
        } catch (t: Throwable) {
            Log.e(TAG, "could not show lock screen for pkg=$pkg", t)
        }
    }

    /** Keyboards and the notification shade open over an app without leaving it. */
    private fun isTransientWindow(pkg: String, className: CharSequence?): Boolean =
        pkg == SYSTEM_UI_PACKAGE || className?.toString() == SOFT_INPUT_WINDOW_CLASS

    /**
     * Packages that stay usable even when they are in the locked list:
     * blocking the home app would bounce between Home and the lock screen
     * forever, blocking a keyboard would stop typing in every app, and
     * blocking the dialer would stop incoming calls being answered. Resolved
     * only on the block path, so a newly chosen launcher or keyboard counts
     * straight away.
     */
    private fun isNeverBlocked(pkg: String): Boolean {
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        if (pkg == home) return true

        val dialer = getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        if (pkg == dialer) return true

        val keyboards = getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
        return keyboards?.any { it.packageName == pkg } == true
    }

    private fun launchSafely(what: String, block: suspend () -> Unit) {
        appScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$what failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "KryptA11y"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val SOFT_INPUT_WINDOW_CLASS = "android.inputmethodservice.SoftInputWindow"
    }
}
