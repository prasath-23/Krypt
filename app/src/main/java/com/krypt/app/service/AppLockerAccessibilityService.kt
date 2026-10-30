package com.krypt.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.krypt.app.common.ScreensInFront
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
 * FR-001 interception core. Observes window events and Android's list of
 * windows ([AppWindows], Amendment 8) and, when a locked app is on screen
 * and nothing allows it - no active grant, no every-day time left
 * ([AccessPolicy]) - replaces it with [LockScreenActivity] (Amendment 2:
 * locked apps are blocked, not covered by an overlay). Krypt's own windows -
 * the lock screen itself and the Guardian popup (FR-011) - are never blocked.
 *
 * Also hosts the parts of Krypt that must keep running: the new-install
 * auto-lock (FR-003, via [NewInstallLocker]), counting every-day time
 * (Amendment 3, via [DailyAllowanceMeter], fed by what is on screen and
 * [DeviceStateWatcher]), and the time of the apps on screen - in front, in
 * split screen, in a small window or picture-in-picture: each is blocked the
 * moment its unlock or every-day time ends, with a countdown for the last
 * five minutes (FR-013, Amendments 7, 8 and 9, via [TimeLeftWatcher] and
 * [TimeLeftPill]).
 *
 * Apart from the 15-minute health check, this service is all Krypt runs in
 * the background (Amendments 5 and 10). Android binds it while it is switched
 * on, restarts it if the process dies, and calls it when something happens;
 * between calls it only waits on the timers above. If Android starts it but
 * never connects it, [ConnectWatchdog] restarts Krypt (Amendment 10).
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

    /** The app whose window has focus; keyboard and notification-shade windows don't change it. */
    private var foregroundPackage: String? = null

    /**
     * Every app with a window on screen (Amendment 8): the one in front, and
     * any in split screen, a small floating window or picture-in-picture.
     * Their time counts, and each is blocked when it ends.
     */
    private var showing: Set<String> = emptySet()

    /** The apps among [showing] in picture-in-picture. */
    private var inPip: Set<String> = emptySet()

    /** What the meter was last told is in use. */
    private var toldInUse: Map<String, Boolean>? = null

    /** Apps worked out to be ones Krypt never blocks ([isNeverBlocked]); forgotten when the app in front changes. */
    private val neverBlocked = HashMap<String, Boolean>()

    /** The screen is on and unlocked, as [DeviceStateWatcher] last said. */
    private var screenOn = true

    /**
     * The app [blockLaunch] last sent away, and when, until another app
     * comes to the front: the meter and the time watcher can find its time
     * up together.
     */
    private var sentAway: String? = null
    private var sentAwayAt = 0L

    /** When Krypt last asked Android to close each app's windows ([closeWindowsOf]). */
    private val closingAt = HashMap<String, Long>()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val appWindows = AppWindows(
        scope = serviceScope,
        background = Dispatchers.IO,
        appWindows = ::listAppWindows,
        lookUp = ::rootPackage,
        onFound = { refreshScreen() },
    )

    private var deviceState: DeviceStateWatcher? = null
    private var timeLeftPill: TimeLeftPill? = null
    private var timeLeft: TimeLeftWatcher? = null
    private var connectWatchdog: ConnectWatchdog? = null

    private var watchingInstalls = false
    private val packageAddedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            launchSafely("auto-lock $pkg") { newInstallLocker.onPackageAdded(pkg, replacing) }
        }
    }

    /** Android created the service; connecting it should follow at once ([ConnectWatchdog]). */
    override fun onCreate() {
        super.onCreate()
        ServiceState.created = true
        connectWatchdog = ConnectWatchdog(
            scope = serviceScope,
            isConnected = { ServiceState.connected },
            screenInFront = { ScreensInFront.any },
            restarts = RestartLog(getSharedPreferences(RestartLog.PREFS_NAME, MODE_PRIVATE), System::currentTimeMillis),
            restart = { Process.killProcess(Process.myPid()) },
        ).also { it.start() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceState.connected = true
        connectWatchdog?.stop()
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
            recheck = { refreshScreen(why = "rechecked") },
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
                // Just after Krypt starts this is its first look at what is on screen; and the list
                // changing can lock or unlock an app that is.
                refreshScreen()
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
        when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowShown(ev)
            // A window came or went, took focus, or went into picture-in-picture.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (ev.windowChanges and SCREEN_CHANGES != 0) refreshScreen()
            else -> Unit // no other events are asked for
        }
    }

    private fun onWindowShown(ev: AccessibilityEvent) {
        val pkg = ev.packageName?.toString() ?: return
        // The countdown showing over the app in front; it doesn't take its place.
        if (pkg == packageName && ev.className?.toString() == TimeLeftPill.ACCESSIBILITY_CLASS) return
        if (isTransientWindow(pkg, ev.className)) return
        appWindows.record(ev.windowId, pkg)
        // A window showing isn't always its app coming to the front: during a home gesture the
        // launcher shows behind the app, which keeps focus when the gesture is cancelled.
        refreshScreen(fallback = pkg, why = "in front")
        blockIfNeeded(pkg, why = "in front")
    }

    /**
     * Work out again which app is in front and which are on screen, from
     * Android's list of windows (Amendment 8), and count, keep time and block
     * for them. When Android lists no windows, the app whose window just
     * showed ([fallback]) is taken to be in front, as before.
     */
    private fun refreshScreen(fallback: String? = null, why: String = "on screen") {
        val screen = appWindows.onScreen()
        val front = screen?.inFront ?: fallback ?: foregroundPackage
        if (front != foregroundPackage) {
            neverBlocked.clear()
            sentAway = null
        }
        foregroundPackage = front
        showing = screen?.showing.orEmpty() + listOfNotNull(front)
        inPip = screen?.pip.orEmpty()
        val inUse = showing.associateWith { it in lockedPackagesCache }
        if (inUse != toldInUse) {
            toldInUse = inUse
            Log.i(TAG, "on screen: ${inUse.keys.joinToString()}")
            // Krypt's own windows too, so they stop the time of the apps they cover.
            dailyMeter.onInUse(inUse)
        }
        showing.forEach { blockIfNeeded(it, why) }
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
        } else {
            Log.i(TAG, "unbound by Android while switched on")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connectWatchdog?.stop()
        ServiceState.connected = false
        ServiceState.created = false
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

    /** An app on screen has no time left ([TimeLeftWatcher]): its unlock or every-day time ended. */
    private fun onTimeUp(pkg: String) {
        if (pkg in showing) blockIfNeeded(pkg, why = "time up")
    }

    /** An app's every-day time ran out, or its rule ended, while it is on screen. */
    private fun onDailyAccessEnded(pkg: String) {
        if (pkg in showing) blockIfNeeded(pkg, why = "every-day time up")
    }

    /**
     * Keep time for the locked apps on screen ([TimeLeftWatcher]) while the
     * screen is on. Called on everything that can move when their time ends.
     */
    private fun keepTime() {
        val apps = if (!screenOn) {
            emptyList()
        } else {
            showing.filter { it != packageName && it in lockedPackagesCache && !isNeverBlockedCached(it) }
        }
        timeLeft?.watch(apps)
    }

    private fun isNeverBlockedCached(pkg: String): Boolean = neverBlocked.getOrPut(pkg) { isNeverBlocked(pkg) }

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
        if (foregroundPackage == packageName) {
            // Krypt's own screen is in front, so only the app's small window can be left over it, and
            // starting the lock screen again changes nothing: ask Android to close that window.
            closeWindowsOf(pkg)
            return
        }
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
        // An app in a small window - picture-in-picture, a floating window - can stay over the lock screen.
        if (pkg != foregroundPackage || pkg in inPip) closeWindowsOf(pkg)
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

    /**
     * Ask Android to close [pkg]'s windows. A picture-in-picture window can be
     * closed so; an app's own window refuses, and the lock screen covers it.
     * Off the main thread, since it asks the window itself.
     */
    private fun closeWindowsOf(pkg: String) {
        val ids = appWindows.windowsOf(pkg).toSet()
        val now = SystemClock.elapsedRealtime()
        if (ids.isEmpty() || now - (closingAt[pkg] ?: Long.MIN_VALUE / 2) < SENT_AWAY_MS) return
        closingAt[pkg] = now
        serviceScope.launch(Dispatchers.IO) {
            try {
                for (window in windows) {
                    if (window.id !in ids) continue
                    val closed = window.root?.performAction(AccessibilityNodeInfo.ACTION_DISMISS) == true
                    Log.i(TAG, "closing a window of pkg=$pkg: ${if (closed) "closed" else "can't be closed"}")
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not close the windows of pkg=$pkg", e)
            }
        }
    }

    /** The apps' windows on screen, from Android; null when it lists no windows at all (just unbound, say). */
    private fun listAppWindows(): List<AppWindow>? = try {
        val all = windows
        if (all.isEmpty()) {
            null
        } else {
            all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .map { AppWindow(it.id, it.isFocused, it.isActive, it.isInPictureInPictureMode) }
        }
    } catch (e: RuntimeException) {
        null
    }

    /** Window [windowId]'s app, from its root node: its package name, nothing else of it. */
    private fun rootPackage(windowId: Int): String? = try {
        windows.firstOrNull { it.id == windowId }?.root?.packageName?.toString()
    } catch (e: RuntimeException) {
        null
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

        /** Window changes that can change what is on screen: added, removed, focus, use, picture-in-picture. */
        const val SCREEN_CHANGES = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
            AccessibilityEvent.WINDOWS_CHANGE_PIP
    }
}
