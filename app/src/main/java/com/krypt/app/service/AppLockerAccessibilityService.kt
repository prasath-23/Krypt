package com.krypt.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
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
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.di.ApplicationScope
import com.krypt.app.notifications.NotificationHelper
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
 * auto-lock (FR-003, via [NewInstallLocker]), counting every-day time
 * (Amendment 3, via [DailyAllowanceMeter], fed by window changes and
 * [DeviceStateWatcher]), and the time of the app in front: it is blocked
 * the moment its unlock or every-day time ends, with a countdown over it
 * for its last two minutes (FR-013, Amendment 7, via [TimeLeftWatcher] and
 * [TimeLeftPill]).
 *
 * Apart from the 6-hour health check, this service is all Krypt runs in the
 * background (Amendment 5). Android binds it while it is switched on,
 * restarts it if the process dies, and calls it when something happens;
 * between calls it only waits on the timers above.
 */
@AndroidEntryPoint
class AppLockerAccessibilityService : AccessibilityService() {

    @Inject lateinit var lockedAppsRepo: LockedAppsRepository
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var newInstallLocker: NewInstallLocker
    @Inject lateinit var dailyMeter: DailyAllowanceMeter
    @Inject lateinit var dayClock: TrustedDayClock
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    @Volatile private var lockedPackagesCache: Set<String> = emptySet()

    /** Setup is finished, so switching the service off is worth an alert. */
    @Volatile private var setupFinished = false

    /** The app the user is in; keyboard and notification-shade windows don't change it. */
    private var foregroundPackage: String? = null

    /** Whether [foregroundPackage] is one Krypt never blocks ([isNeverBlocked]), worked out once per app. */
    private var neverBlockedInFront: Pair<String, Boolean>? = null

    /** The screen is on and unlocked, as [DeviceStateWatcher] last said. */
    private var screenOn = true

    /**
     * The app [blockLaunch] last sent away, and when, until the next window
     * change: the meter and the time watcher can find its time up together.
     */
    private var sentAway: String? = null
    private var sentAwayAt = 0L

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var deviceState: DeviceStateWatcher? = null
    private var timeLeftPill: TimeLeftPill? = null
    private var timeLeft: TimeLeftWatcher? = null

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
        serviceScope.launch { settings.settings.collect { setupFinished = it.onboardingComplete } }
        val pill = TimeLeftPill(this).also { timeLeftPill = it }
        timeLeft = TimeLeftWatcher(
            scope = serviceScope,
            grantMs = sessionStore::remainingMs,
            usableMs = dailyMeter::usableMs,
            display = pill,
            onGrantEnded = { dailyMeter.reevaluate() },
            onTimeUp = ::onTimeUp,
        )
        // A new unlock, every-day time being counted, or a rule changing all move when the app in front's time ends.
        serviceScope.launch { sessionStore.grantEvents.collect { keepTime() } }
        serviceScope.launch { dailyMeter.changes.collect { keepTime() } }
        dailyMeter.attach(serviceScope, ::onDailyAccessEnded)
        deviceState = DeviceStateWatcher(
            this,
            dailyMeter,
            dayClock,
            onScreen = { interactive ->
                screenOn = interactive
                keepTime()
            },
            recheck = {
                foregroundPackage?.let { blockIfNeeded(it, why = "rechecked") }
                keepTime()
            },
        ).also { it.start() }
        watchNewInstalls()
        // Protection is back, so an earlier "Krypt is off" alert is out of date.
        notificationHelper.cancelProtectionOff()
        Log.i(TAG, "connected")
    }

    private fun subscribeToLockedAppsFlow() {
        serviceScope.launch {
            // With the every-day rules loaded first, an app with daily time left
            // is never blocked in the moment after Krypt starts.
            dailyMeter.loaded.first { it }
            lockedAppsRepo.allLockedFlow().collect { set ->
                lockedPackagesCache = set
                // The app in front may have come before the list did, or been locked or unlocked since.
                foregroundPackage?.let { dailyMeter.onForeground(it, locked = it in set) }
                keepTime()
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
        // The countdown showing over the app in front; it doesn't take its place.
        if (pkg == packageName && ev.className?.toString() == TimeLeftPill.ACCESSIBILITY_CLASS) return
        if (!isTransientWindow(pkg, ev.className)) {
            if (pkg != foregroundPackage) neverBlockedInFront = null
            foregroundPackage = pkg
            sentAway = null
            // Also for Krypt's own windows, so they stop an app's daily time.
            dailyMeter.onForeground(pkg, locked = pkg in lockedPackagesCache)
        }

        blockIfNeeded(pkg, why = "in front")
        keepTime()
    }

    override fun onInterrupt() {
        // no-op; required by AccessibilityService.
    }

    /**
     * Android unbinds the service when it is switched off, but also while a
     * UI test drives the phone or another user is in front. Only switching it
     * off takes it out of the enabled setting, and that is said at once, not
     * at the next health check (Amendment 5). Before setup is finished the
     * service is expected to be off, as in AccessibilityHealthWorker.
     *
     * Posted here, on the main thread, so that switching the service straight
     * back on cancels it afterwards in [onServiceConnected], never before.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (!EnabledAccessibilityServices.includes(enabled, packageName, AppLockerAccessibilityService::class.java.name)) {
            Log.i(TAG, "switched off")
            if (setupFinished) notificationHelper.notifyProtectionOff()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        timeLeft?.watch(null) // stops its timer and takes the countdown down
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

    /** The app in front has no time left ([TimeLeftWatcher]): its unlock or every-day time ended. */
    private fun onTimeUp(pkg: String) {
        if (pkg == foregroundPackage) blockIfNeeded(pkg, why = "time up")
    }

    /** The app's every-day time ran out, or its rule ended, while it is open. */
    private fun onDailyAccessEnded(pkg: String) {
        if (pkg == foregroundPackage) blockIfNeeded(pkg, why = "every-day time up")
    }

    /**
     * Keep time for the app in front ([TimeLeftWatcher]) while the screen is
     * on: a locked app Krypt may block. Called on everything that can move
     * when its time ends.
     */
    private fun keepTime() {
        val pkg = foregroundPackage?.takeIf {
            screenOn && it != packageName && it in lockedPackagesCache && !isNeverBlockedInFront(it)
        }
        timeLeft?.watch(pkg)
    }

    private fun isNeverBlockedInFront(pkg: String): Boolean =
        neverBlockedInFront?.takeIf { it.first == pkg }?.second
            ?: isNeverBlocked(pkg).also { neverBlockedInFront = pkg to it }

    /** Block [pkg] if Krypt locks it and nothing allows it right now ([AccessPolicy]). [why] is logged. */
    private fun blockIfNeeded(pkg: String, why: String) {
        if (pkg == packageName || pkg !in lockedPackagesCache) return
        val decision = AccessPolicy.decide(
            isLocked = true,
            hasGrant = sessionStore.isUnlockedNow(pkg),
            daily = dailyMeter.access(pkg),
        )
        if (decision !is AccessDecision.Block || isNeverBlocked(pkg)) return
        // Already on its way out: sending it away twice would only start the lock screen twice.
        if (pkg == sentAway && SystemClock.elapsedRealtime() - sentAwayAt < SENT_AWAY_MS) return
        blockLaunch(pkg, why)
    }

    /**
     * Close the locked app and show the lock screen. The app is sent behind
     * the home screen first, so it is never directly under the lock screen:
     * if Krypt's process dies (a crash, an update), Android removes the lock
     * screen and the home screen shows, not the app. Leaving the lock screen
     * also goes to the home screen, and bringing the app back (launcher,
     * Recents, a notification) fires another event.
     */
    private fun blockLaunch(pkg: String, why: String) {
        timeLeftPill?.hide() // not left over the lock screen
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Started together with no transition animation, so the app is
        // hidden immediately and the home screen never flashes up.
        val noAnimation = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        try {
            startActivities(arrayOf(home, LockScreenActivity.intentFor(this, pkg)), noAnimation)
            sentAway = pkg
            sentAwayAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "blocked pkg=$pkg ($why)")
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

        /** Long enough for the lock screen to come up, short enough to try again if it didn't. */
        const val SENT_AWAY_MS = 2_000L
    }
}
