package com.krypt.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Krypt Application entry point.
 *
 * Responsibilities are intentionally minimal at the WP01 foundation stage:
 *  - Hosts Hilt's DI graph via [HiltAndroidApp].
 *
 * Later WPs wire additional side-effects here:
 *  - WP07: create the Security-Alerts notification channel.
 *  - WP16: start the watchdog foreground service and WorkManager heartbeat.
 *  - WP06: bootstrap the in-memory LockerSessionStore from persisted grants.
 *
 * Do NOT perform any network work here. Krypt does not declare INTERNET.
 */
@HiltAndroidApp
class KryptApplication : Application()
