package com.krypt.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
        val channel = NotificationChannel(
            NotificationChannels.SECURITY_ALERTS,
            "Security Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when Krypt auto-locks a new app or its protection is switched off"
            enableLights(true)
            setShowBadge(true)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}
