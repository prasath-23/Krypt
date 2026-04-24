package com.krypt.app.notifications

/** Central registry of Krypt notification channel IDs. */
object NotificationChannels {
    const val SECURITY_ALERTS: String = "krypt.security_alerts"
    /** Low-importance channel for the watchdog foreground service (WP16). */
    const val WATCHDOG: String = "krypt.watchdog"
}
