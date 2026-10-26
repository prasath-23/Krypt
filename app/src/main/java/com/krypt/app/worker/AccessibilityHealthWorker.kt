package com.krypt.app.worker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Checks every 6 hours that Krypt's Accessibility Service is running and,
 * if it isn't after setup, posts the "Krypt is off" alert so the user turns
 * it back on.
 *
 * Switching the service off is reported at once by the service itself
 * (AppLockerAccessibilityService.onUnbind). This check is the safety net for
 * what no event reports: the service switched on but not running, for
 * example after a reboot it never came back from (FR-010 / SC-004,
 * Amendment 5).
 */
@HiltWorker
class AccessibilityHealthWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notificationHelper: NotificationHelper,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Before setup is finished the service is expected to be off.
        if (!settings.settings.first().onboardingComplete) return Result.success()
        val am = applicationContext.getSystemService(AccessibilityManager::class.java)
            ?: return Result.retry()
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val isOn = enabled.any { it.resolveInfo?.serviceInfo?.packageName == applicationContext.packageName }
        if (!isOn) notificationHelper.notifyProtectionOff()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME: String = "krypt.accessibility_health"
        const val PERIOD_HOURS: Long = 6L

        /**
         * KEEP, so that a process start leaves the schedule alone; UPDATE only
         * when the check scheduled now has another period, as on an install
         * that had the 15-minute check. Reads WorkManager's database, so call
         * it off the main thread.
         */
        fun schedule(workManager: WorkManager) {
            val scheduledPeriod = workManager.getWorkInfosForUniqueWork(UNIQUE_NAME).get()
                .firstOrNull { !it.state.isFinished }?.periodicityInfo?.repeatIntervalMillis
            val policy = if (scheduledPeriod == null || scheduledPeriod == TimeUnit.HOURS.toMillis(PERIOD_HOURS)) {
                ExistingPeriodicWorkPolicy.KEEP
            } else {
                ExistingPeriodicWorkPolicy.UPDATE
            }
            val req = PeriodicWorkRequestBuilder<AccessibilityHealthWorker>(PERIOD_HOURS, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, policy, req)
        }
    }
}
