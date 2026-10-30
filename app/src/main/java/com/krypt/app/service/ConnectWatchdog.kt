package com.krypt.app.service

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether Krypt's accessibility service is running in this process (Amendment
 * 10). Android can create the service and still never connect it: Krypt then
 * runs but sees nothing. Read by the health check and [ConnectWatchdog].
 */
object ServiceState {
    /** Android created the service in this process and hasn't destroyed it. */
    @Volatile var created = false
        internal set

    /** Android connected it: window events reach Krypt. */
    @Volatile var connected = false
        internal set
}

/**
 * Restarts Krypt when Android has started its accessibility service but not
 * connected it within [CONNECT_TIMEOUT_MS] (Amendment 10). Krypt then sees no
 * app opening, counts no time and blocks nothing, and Android doesn't try
 * again by itself. It was seen when Krypt's process was killed while it was
 * starting, after a reboot under load. A process that dies is started again
 * by Android, which then connects the service.
 *
 * Never under one of Krypt's own screens: it waits until none is in front
 * ([screenInFront]). At most [RestartLog.MAX_PER_HOUR] times an hour, so a
 * phone that is only slow can't make it restart again and again.
 */
class ConnectWatchdog(
    private val scope: CoroutineScope,
    private val isConnected: () -> Boolean,
    private val screenInFront: () -> Boolean,
    private val restarts: RestartLog,
    private val restart: () -> Unit,
) {
    private var job: Job? = null

    /** The service was just created: start waiting for it to be connected. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            while (!isConnected() && screenInFront()) delay(SCREEN_RETRY_MS)
            if (isConnected()) return@launch
            if (restarts.tryNote()) {
                Log.w(TAG, "not connected ${CONNECT_TIMEOUT_MS / 1_000} s after Android started it: restarting Krypt")
                restart()
            } else {
                Log.w(TAG, "not connected, but restarted ${RestartLog.MAX_PER_HOUR} times in the last hour already")
            }
        }
    }

    /** Connected, or destroyed: nothing to wait for. */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Connecting takes milliseconds; a phone under heavy load can take seconds. */
        const val CONNECT_TIMEOUT_MS = 60_000L

        /** How often to look again while one of Krypt's own screens is in front. */
        const val SCREEN_RETRY_MS = 15_000L

        private const val TAG = "KryptA11y"
    }
}

/** When Krypt last restarted itself ([ConnectWatchdog]), kept across the restarts. */
class RestartLog(private val prefs: SharedPreferences, private val nowMs: () -> Long) {

    /** Notes a restart now and returns true, unless [MAX_PER_HOUR] happened in the last hour. */
    @SuppressLint("ApplySharedPref") // written at once: the process is killed right after
    fun tryNote(): Boolean {
        val now = nowMs()
        val recent = prefs.getString(KEY, "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it in 0 until HOUR_MS }
        if (recent.size >= MAX_PER_HOUR) return false
        prefs.edit().putString(KEY, (recent + now).joinToString(",")).commit()
        return true
    }

    companion object {
        const val PREFS_NAME = "krypt_service_restarts"
        const val MAX_PER_HOUR = 3
        private const val KEY = "restarts"
        private const val HOUR_MS = 60 * 60_000L
    }
}
