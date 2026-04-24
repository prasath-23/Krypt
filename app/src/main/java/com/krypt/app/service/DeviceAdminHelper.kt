package com.krypt.app.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Small facade around [DevicePolicyManager] used by the onboarding UI. */
@Singleton
class DeviceAdminHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dpm: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val componentName: ComponentName by lazy {
        ComponentName(context, KryptDeviceAdminReceiver::class.java)
    }

    fun isActive(): Boolean = dpm.isAdminActive(componentName)

    fun createActivationIntent(explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        }
}
