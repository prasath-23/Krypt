package com.krypt.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.krypt.app.common.Clock
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.UnlockGrantRepository
import com.krypt.app.di.ApplicationScope
import com.krypt.app.notifications.SecurityAlertsChannel
import com.krypt.app.service.KryptWatchdogService
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
 *  - Watchdog foreground service + 15-min AccessibilityHealthWorker (WP16)
 *
 * No network work. Ever.
 */
@HiltAndroidApp
class KryptApplication : Application(), Configuration.Provider {

    @Inject lateinit var securityAlertsChannel: SecurityAlertsChannel
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var grantRepo: UnlockGrantRepository
    @Inject lateinit var clock: Clock
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        securityAlertsChannel.ensureCreated()
        restoreUnlockGrants()
        KryptWatchdogService.start(this)
        AccessibilityHealthWorker.schedule(WorkManager.getInstance(this))
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

    private companion object {
        const val TAG = "KryptApp"
    }
}
