package com.krypt.app.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * FR-004: "New App Protected" — fires on PackageReceiver auto-lock.
     *
     * Notification ID is hash-of-package so repeated installs of the same
     * package REPLACE (not stack) their alert.
     */
    fun notifyAppLocked(packageName: String, displayName: String) {
        if (!permissionGranted()) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; dropping lock notification for $packageName")
            return
        }
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
        val notification = NotificationCompat.Builder(context, NotificationChannels.SECURITY_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(context.getString(R.string.notif_app_locked_title))
            .setContentText(context.getString(R.string.notif_app_locked_body, displayName, packageName))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.notif_app_locked_body, displayName, packageName)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val id = "krypt-lock-$packageName".hashCode()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun permissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val EXTRA_PACKAGE: String = "com.krypt.app.EXTRA_PACKAGE"
        private const val TAG = "KryptNotif"
    }
}
