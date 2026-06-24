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
import com.krypt.app.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic (15 min) check that Krypt's Accessibility Service is enabled.
 * If absent, posts a nag notification so the user re-enables it.
 * FR-010 / SC-004 soft-guarantee (WorkManager 15-min minimum).
 */
@HiltWorker
class AccessibilityHealthWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val am = applicationContext.getSystemService(AccessibilityManager::class.java)
            ?: return Result.retry()
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val isOn = enabled.any { it.resolveInfo?.serviceInfo?.packageName == applicationContext.packageName }
        if (!isOn) notificationHelper.notifyAppLocked(
            packageName = applicationContext.packageName,
            displayName = "Accessibility disabled",
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME: String = "krypt.accessibility_health"
        const val PERIOD_MIN: Long = 15L

        fun schedule(workManager: WorkManager) {
            val req = PeriodicWorkRequestBuilder<AccessibilityHealthWorker>(PERIOD_MIN, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }
    }
}
