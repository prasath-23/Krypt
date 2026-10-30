package com.krypt.app

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.krypt.app.common.Clock
import com.krypt.app.common.ScreensInFront
import com.krypt.app.common.TrustedDayClock
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.UnlockGrantRepository
import com.krypt.app.data.daily.DailyAllowanceMeter
import com.krypt.app.data.daily.DailyAllowanceRepository
import com.krypt.app.data.settings.AppearanceStore
import com.krypt.app.di.ApplicationScope
import com.krypt.app.notifications.NotificationChannels
import com.krypt.app.notifications.SecurityAlertsChannel
import com.krypt.app.worker.AccessibilityHealthWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Krypt Application.
 *
 * Lifecycle hooks:
 *  - HiltAndroidApp DI bootstrap
 *  - Security-Alerts notification channel (WP07)
 *  - Restore the unlock grants this boot had before the process last died
 *  - The 15-minute AccessibilityHealthWorker (WP16, Amendment 10), scheduled off the main thread
 *  - [ScreensInFront], so Krypt never restarts itself under its own screens
 *
 * No foreground service: the accessibility service is all Krypt runs in the
 * background (Amendment 5). No network work. Ever.
 */
@HiltAndroidApp
class KryptApplication : Application(), Configuration.Provider {

    @Inject lateinit var securityAlertsChannel: SecurityAlertsChannel
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var grantRepo: UnlockGrantRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var dayClock: TrustedDayClock
    @Inject lateinit var dailyMeter: DailyAllowanceMeter
    @Inject lateinit var dailyRepo: DailyAllowanceRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var appearanceStore: AppearanceStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ScreensInFront)
        securityAlertsChannel.ensureCreated()
        restoreUnlockGrants()
        // The chosen theme must hold before any window exists: AppCompat's
        // in-process default now; the system's copy (Android 12+, kept across
        // restarts) is only re-synced, so its system call stays off the main thread.
        appearanceStore.applyStored()
        appScope.launch { appearanceStore.syncSystem() }
        startDailyAllowances()
        scheduleHealthCheck()
        deleteWatchdogChannel()
    }

    /**
     * Without this, an app unlocked by the Guardian re-locks whenever the
     * process restarts. Done before anything else runs, so the accessibility
     * service never sees the grants missing.
     */
    private fun restoreUnlockGrants() {
        sessionStore.restore()
        appScope.launch {
            try {
                grantRepo.deleteExpired(clock.nowMs())
            } catch (e: Exception) {
                Log.e(TAG, "could not prune old unlock grants", e)
            }
        }
    }

    /**
     * Take the trusted-day anchor now, at process start (normally at boot), so
     * a date changed later can't become it; the meter starts loading its rules
     * as it's injected. Then prune, by the trusted day and only while it is
     * known: rules that ended over a day ago, and usage older than 35 days
     * that no rule still covers.
     */
    private fun startDailyAllowances() {
        val now = dayClock.nowMs() ?: return
        appScope.launch {
            try {
                val today = now / DAY_MS
                dailyRepo.prune(rulesEndedBefore = today - 1, usageBeforeMinute = (today - 35) * 1440)
            } catch (e: Exception) {
                Log.e(TAG, "could not prune every-day data", e)
            }
        }
    }

    /**
     * Off the main thread, together with WorkManager's own setup. This runs at
     * every process start, including the one Android makes to bind the
     * accessibility service after a reboot, where a start that takes too long
     * gets the process killed before the service is up.
     */
    private fun scheduleHealthCheck() {
        appScope.launch {
            try {
                AccessibilityHealthWorker.schedule(WorkManager.getInstance(this@KryptApplication))
            } catch (e: Exception) {
                Log.e(TAG, "could not schedule the health check", e)
            }
        }
    }

    /**
     * Phones updated from a version with the watchdog still list its channel
     * in Settings. A deleted channel no longer reads back, so this deletes it
     * once, not at every start.
     */
    private fun deleteWatchdogChannel() {
        appScope.launch {
            try {
                val notifications = NotificationManagerCompat.from(this@KryptApplication)
                if (notifications.getNotificationChannel(NotificationChannels.RETIRED_WATCHDOG) != null) {
                    notifications.deleteNotificationChannel(NotificationChannels.RETIRED_WATCHDOG)
                }
            } catch (e: Exception) {
                Log.e(TAG, "could not delete the watchdog's channel", e)
            }
        }
    }

    private companion object {
        const val TAG = "KryptApp"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
