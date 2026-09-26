package com.krypt.app.notifications

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.krypt.app.R
import com.krypt.app.ui.main.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts Krypt's user-visible notifications via the Security-Alerts channel.
 * Without POST_NOTIFICATIONS they are dropped quietly (FR-029).
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * FR-004: "New App Protected" — fires when a new install is auto-locked.
     *
     * Notification ID is hash-of-package so repeated installs of the same
     * package REPLACE (not stack) their alert.
     */
    fun notifyAppLocked(packageName: String, displayName: String) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PACKAGE, packageName)
        }
        val pending = PendingIntent.getActivity(
            context,
            /* requestCode = */ packageName.hashCode(),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = context.getString(R.string.notif_app_locked_body, displayName, packageName)
        val notification = alert(context.getString(R.string.notif_app_locked_title), body, pending)
        post("krypt-lock-$packageName".hashCode(), notification)
    }

    /**
     * Krypt's accessibility service is switched off, so locked apps open
     * freely. Tapping the alert opens the Accessibility settings.
     */
    fun notifyProtectionOff() {
        val pending = PendingIntent.getActivity(
            context,
            PROTECTION_OFF_ID,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = alert(
            context.getString(R.string.notif_protection_off_title),
            context.getString(R.string.notif_protection_off_body),
            pending,
        )
        post(PROTECTION_OFF_ID, notification)
    }

    private fun alert(title: String, body: String, tap: PendingIntent): Notification =
        NotificationCompat.Builder(context, NotificationChannels.SECURITY_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; dropping notification $id")
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Revoked between the check and the call.
            Log.w(TAG, "notification $id rejected", e)
        }
    }

    companion object {
        const val EXTRA_PACKAGE: String = "com.krypt.app.EXTRA_PACKAGE"
        private const val PROTECTION_OFF_ID = 0x4B504F /* "KPO" */
        private const val TAG = "KryptNotif"
    }
}
