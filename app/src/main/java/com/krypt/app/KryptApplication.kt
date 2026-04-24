package com.krypt.app

import android.app.Application
import com.krypt.app.notifications.SecurityAlertsChannel
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Krypt Application entry point.
 *
 * Lifecycle hooks wired so far:
 *  - [HiltAndroidApp] bootstrap (WP01).
 *  - [SecurityAlertsChannel.ensureCreated] — registers the notification
 *    channel on API 26+ so [NotificationHelper] has somewhere to post
 *    (WP07).
 *
 * WP16 adds: start watchdog FGS + schedule AccessibilityHealthWorker.
 *
 * Do NOT perform any network work here. Krypt does not declare INTERNET.
 */
@HiltAndroidApp
class KryptApplication : Application() {

    @Inject lateinit var securityAlertsChannel: SecurityAlertsChannel

    override fun onCreate() {
        super.onCreate()
        securityAlertsChannel.ensureCreated()
    }
}
