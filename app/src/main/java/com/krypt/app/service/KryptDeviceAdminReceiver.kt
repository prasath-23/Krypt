package com.krypt.app.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.krypt.app.R

/**
 * Device-Admin receiver. Activation makes the OS refuse to uninstall Krypt
 * until the user first deactivates admin — consumer-grade deterrent (FR-012).
 */
class KryptDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled; Krypt is now uninstallable")
    }

    private companion object { const val TAG = "KryptAdmin" }
}
