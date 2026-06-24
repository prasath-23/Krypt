package com.krypt.app

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.krypt.app.notifications.SecurityAlertsChannel
import com.krypt.app.service.KryptWatchdogService
import com.krypt.app.worker.AccessibilityHealthWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Krypt Application.
 *
 * Lifecycle hooks:
 *  - HiltAndroidApp DI bootstrap
 *  - Security-Alerts notification channel (WP07)
 *  - Watchdog foreground service + 15-min AccessibilityHealthWorker (WP16)
 *
 * No network work. Ever.
 */
@HiltAndroidApp
class KryptApplication : Application(), Configuration.Provider {

    @Inject lateinit var securityAlertsChannel: SecurityAlertsChannel
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        securityAlertsChannel.ensureCreated()
        startWatchdog()
        AccessibilityHealthWorker.schedule(WorkManager.getInstance(this))
    }

    private fun startWatchdog() {
        val intent = Intent(this, KryptWatchdogService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
