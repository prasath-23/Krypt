package com.krypt.app.common

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether one of Krypt's own screens is in front (resumed), so that Krypt
 * never restarts itself under the person using it (Amendment 10).
 * [com.krypt.app.KryptApplication] registers it; callbacks come on the main thread.
 */
object ScreensInFront : Application.ActivityLifecycleCallbacks {
    @Volatile private var resumed = 0

    val any: Boolean get() = resumed > 0

    override fun onActivityResumed(activity: Activity) {
        resumed++
    }

    override fun onActivityPaused(activity: Activity) {
        resumed = (resumed - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
