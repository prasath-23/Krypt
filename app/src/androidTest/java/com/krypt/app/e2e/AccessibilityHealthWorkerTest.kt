package com.krypt.app.e2e

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.notifications.NotificationHelper
import com.krypt.app.notifications.SecurityAlertsChannel
import com.krypt.app.service.AppLockerAccessibilityService
import com.krypt.app.service.EnabledAccessibilityServices
import com.krypt.app.worker.AccessibilityHealthWorker
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The 15-minute health check (FR-010, Amendments 5 and 10). Krypt's
 * accessibility service doesn't run during instrumented tests, which is
 * exactly the case it alerts on.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccessibilityHealthWorkerTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var state: TestState
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var securityAlertsChannel: SecurityAlertsChannel

    private val notifications: NotificationManager
        get() = targetContext.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        hilt.inject()
        // A runtime permission only from Android 13; earlier, granting it throws "Unknown permission".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        securityAlertsChannel.ensureCreated() // KryptApplication does this in the app
        notifications.cancelAll()
    }

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<AccessibilityHealthWorker>(targetContext)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = AccessibilityHealthWorker(appContext, workerParameters, notificationHelper, settings)
            })
            .build()
        return runBlocking { worker.doWork() }
    }

    /**
     * Whether an alert titled [title] shows up within [withinMs]. Posting is
     * asynchronous: notify() returns before the system lists the notification.
     */
    private fun alertShown(title: String, withinMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + withinMs
        while (true) {
            val shown = notifications.activeNotifications.any {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
            }
            if (shown || SystemClock.uptimeMillis() >= deadline) return shown
            Thread.sleep(100)
        }
    }

    /**
     * The service doesn't run during these tests. Switched off in Settings, as
     * on a fresh device, that is "Krypt is off"; left switched on there by an
     * earlier run, it is "Krypt has stopped" (Amendment 10).
     */
    private fun expectedAlert(): String {
        val enabled = Settings.Secure.getString(targetContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val on = EnabledAccessibilityServices.includes(
            enabled,
            targetContext.packageName,
            AppLockerAccessibilityService::class.java.name,
        )
        return if (on) "Krypt has stopped" else "Krypt is off"
    }

    @Test
    fun afterSetup_serviceNotRunning_postsTheAlertThatSaysWhatToDo() {
        state.reset(onboardingComplete = true, pinConfigured = true)
        val title = expectedAlert()

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue("no '$title' alert", alertShown(title, withinMs = 5_000))
    }

    @Test
    fun beforeSetupIsFinished_staysQuiet() {
        state.reset(onboardingComplete = false, pinConfigured = false)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertFalse("alert shown before setup was finished", alertShown(expectedAlert(), withinMs = 2_000))
    }
}
