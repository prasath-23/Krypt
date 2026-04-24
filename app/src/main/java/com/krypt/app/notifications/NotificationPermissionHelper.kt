package com.krypt.app.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Helpers for the API-33+ runtime `POST_NOTIFICATIONS` permission flow.
 * Used by WP12's OnboardingScreen step 4.
 */
object NotificationPermissionHelper {

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun rationaleNeeded(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.POST_NOTIFICATIONS,
            )

    /** Register once during Activity.onCreate; call launcher.launch(POST_NOTIFICATIONS). */
    fun createRequestLauncher(
        caller: ComponentActivity,
        onResult: (Boolean) -> Unit,
    ): ActivityResultLauncher<String> = caller.registerForActivityResult(RequestPermission(), onResult)
}
