package com.krypt.app.worker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.notifications.NotificationHelper
import com.krypt.app.service.AppLockerAccessibilityService
import com.krypt.app.service.EnabledAccessibilityServices
import com.krypt.app.service.ServiceState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Checks every 15 minutes that Krypt's Accessibility Service is running
 * (Amendment 10; every 6 hours under Amendment 5).
 *
 * A phone that stops background apps can stop Krypt with it, and Android may
 * then not start Krypt again by itself: until something does, Krypt sees no
 * app opening, counts no time and blocks nothing. Running this check starts
 * Krypt's process, and Android connects the service in it again when it
 * still holds it. If the service still isn't running, the alert says what
 * to do: "Krypt is off" when it is switched off in Settings, "Krypt has
 * stopped" when it is switched on there but not running (it must be switched
 * off and on again).
 *
 * Switching the service off is reported at once by the service itself
 * (AppLockerAccessibilityService.onUnbind). Android holds this check back
 * while the phone sleeps (FR-010 / SC-004).
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
        val enabled = EnabledAccessibilityServices.includes(
            Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            applicationContext.packageName,
            AppLockerAccessibilityService::class.java.name,
        )
        // This check may have just started Krypt's process, which brings the service
        // back: give Android a moment to connect it before calling it stopped.
        var bound = isBound(am)
        var waited = 0L
        while (enabled && !bound && waited < WAIT_FOR_SERVICE_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            bound = isBound(am)
        }
        val health = serviceHealth(enabled, bound, startingHere = ServiceState.created && !ServiceState.connected)
        Log.i(TAG, "accessibility service: $health")
        when (health) {
            ServiceHealth.RUNNING, ServiceHealth.STARTING -> Unit
            ServiceHealth.STOPPED -> notificationHelper.notifyProtectionStopped()
            ServiceHealth.OFF -> notificationHelper.notifyProtectionOff()
        }
        return Result.success()
    }

    /** Android's list of the accessibility services it has running. */
    private fun isBound(am: AccessibilityManager): Boolean =
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == applicationContext.packageName }

    companion object {
        const val UNIQUE_NAME: String = "krypt.accessibility_health"
        const val PERIOD_MINUTES: Long = 15L

        /** Connecting takes a moment after a process start; on a busy phone a few seconds. */
        const val WAIT_FOR_SERVICE_MS: Long = 20_000L
        private const val POLL_MS = 1_000L
        private const val TAG = "KryptHealth"

        /**
         * KEEP, so that a process start leaves the schedule alone; UPDATE only
         * when the check scheduled now has another period, as on an install
         * that had the 6-hour check. Reads WorkManager's database, so call
         * it off the main thread.
         */
        fun schedule(workManager: WorkManager) {
            val scheduledPeriod = workManager.getWorkInfosForUniqueWork(UNIQUE_NAME).get()
                .firstOrNull { !it.state.isFinished }?.periodicityInfo?.repeatIntervalMillis
            val policy = if (scheduledPeriod == null || scheduledPeriod == TimeUnit.MINUTES.toMillis(PERIOD_MINUTES)) {
                ExistingPeriodicWorkPolicy.KEEP
            } else {
                ExistingPeriodicWorkPolicy.UPDATE
            }
            val req = PeriodicWorkRequestBuilder<AccessibilityHealthWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, policy, req)
        }
    }
}

/** What the health check found ([serviceHealth]). */
enum class ServiceHealth {
    /** Android has the service connected. */
    RUNNING,

    /** Created in this process but not connected yet: [com.krypt.app.service.ConnectWatchdog] restarts Krypt if it stays so. */
    STARTING,

    /** Switched on in Settings but not running: it must be switched off and on again. */
    STOPPED,

    /** Switched off in Settings. */
    OFF,
}

/**
 * The service's health from whether Settings lists it as switched on
 * ([enabledInSettings]), whether Android has it connected ([bound]), and
 * whether it was created in this process and is still waiting to be
 * connected ([startingHere]).
 */
fun serviceHealth(enabledInSettings: Boolean, bound: Boolean, startingHere: Boolean): ServiceHealth = when {
    bound -> ServiceHealth.RUNNING
    !enabledInSettings -> ServiceHealth.OFF
    startingHere -> ServiceHealth.STARTING
    else -> ServiceHealth.STOPPED
}
