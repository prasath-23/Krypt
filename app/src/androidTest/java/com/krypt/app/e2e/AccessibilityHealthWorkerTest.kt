package com.krypt.app.e2e

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.notifications.NotificationHelper
import com.krypt.app.notifications.SecurityAlertsChannel
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
 * The 15-minute health check (FR-010). Instrumented tests run with Krypt's
 * accessibility service switched off, which is exactly the case it alerts on.
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
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
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
     * Whether the alert shows up within [withinMs]. Posting is asynchronous:
     * notify() returns before the system lists the notification.
     */
    private fun protectionOffAlertShown(withinMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + withinMs
        while (true) {
            val shown = notifications.activeNotifications.any {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Krypt protection is off"
            }
            if (shown || SystemClock.uptimeMillis() >= deadline) return shown
            Thread.sleep(100)
        }
    }

    @Test
    fun afterSetup_serviceSwitchedOff_postsTheProtectionOffAlert() {
        state.reset(onboardingComplete = true, pinConfigured = true)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue("no 'Krypt protection is off' alert", protectionOffAlertShown(withinMs = 5_000))
    }

    @Test
    fun beforeSetupIsFinished_staysQuiet() {
        state.reset(onboardingComplete = false, pinConfigured = false)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertFalse("alert shown before setup was finished", protectionOffAlertShown(withinMs = 2_000))
    }
}
