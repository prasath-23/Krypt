package com.krypt.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the "Security Alerts" NotificationChannel (API 26+) on Application
 * start. Idempotent — safe to call on every `onCreate`.
 */
@Singleton
class SecurityAlertsChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureCreated() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NotificationChannels.SECURITY_ALERTS,
            "Security Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when Krypt auto-locks a newly-installed app"
            enableLights(true)
            setShowBadge(true)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}
