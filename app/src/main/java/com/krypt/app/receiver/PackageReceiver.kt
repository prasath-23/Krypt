package com.krypt.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.di.ApplicationScope
import com.krypt.app.notifications.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * FR-003 / FR-004 "Default-Deny Engine".
 *
 * Manifest-registered receiver for `ACTION_PACKAGE_ADDED`. On every new
 * package install:
 *   1. Ignore EXTRA_REPLACING (upgrades / reinstalls).
 *   2. Ignore Krypt's own install.
 *   3. Look up the human-readable display name via PackageManager.
 *   4. Insert into [LockedAppsRepository] with LOCK_STATE=LOCKED, SOURCE=DEFAULT_DENY.
 *   5. Fire a "New App Protected" notification via [NotificationHelper].
 *
 * All DB + PM work runs under `goAsync()` on an app-scoped coroutine.
 */
@AndroidEntryPoint
class PackageReceiver : BroadcastReceiver() {

    @Inject lateinit var lockedAppsRepo: LockedAppsRepository
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            Log.d(TAG, "pkg=$pkg is upgrade/replace — ignored")
            return
        }
        if (pkg == context.packageName) {
            Log.d(TAG, "pkg=$pkg is Krypt itself — ignored")
            return
        }

        val pending = goAsync()
        val pm = context.packageManager

        appScope.launch {
            try {
                val name = resolveDisplayName(pm, pkg)
                lockedAppsRepo.lockNewlyInstalledApp(pkg, name)
                notificationHelper.notifyAppLocked(pkg, name)
                Log.i(TAG, "locked pkg=$pkg name=$name")
            } catch (t: Throwable) {
                Log.e(TAG, "failed to lock pkg=$pkg", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun resolveDisplayName(pm: PackageManager, pkg: String): String =
        withContext(Dispatchers.IO) {
            try {
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
        }

    private companion object { const val TAG = "KryptPkgReceiver" }
}
