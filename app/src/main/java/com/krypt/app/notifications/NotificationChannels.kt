package com.krypt.app.notifications

/** Central registry of Krypt notification channel IDs. */
object NotificationChannels {
    const val SECURITY_ALERTS: String = "krypt.security_alerts"

    /**
     * The channel of the watchdog foreground service, which Amendment 5
     * removed. Deleted on phones that still have it, so it leaves Settings.
     */
    const val RETIRED_WATCHDOG: String = "krypt.watchdog"
}
