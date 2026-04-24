package com.krypt.app.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.krypt.app.R
import com.krypt.app.notifications.NotificationChannels
import com.krypt.app.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Low-importance foreground service that supervises the Accessibility
 * Service. Its existence alone doesn't keep the AccessibilityService alive
 * (the OS does that), but it raises Krypt's overall priority and gives us a
 * restart hook when aggressive OEMs kill the process.
 */
@AndroidEntryPoint
class KryptWatchdogService : Service() {

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, fgsType)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val tap = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NotificationChannels.WATCHDOG)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.watchdog_title))
            .setContentText(getString(R.string.watchdog_body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(android.app.NotificationManager::class.java)
        if (mgr.getNotificationChannel(NotificationChannels.WATCHDOG) == null) {
            mgr.createNotificationChannel(
                android.app.NotificationChannel(
                    NotificationChannels.WATCHDOG,
                    getString(R.string.watchdog_channel),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private companion object { const val NOTIF_ID = 0x4B5730 /* "KW0" */ }
}
