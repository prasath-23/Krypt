package com.krypt.app.receiver

import android.util.Log
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.notifications.NotificationHelper
import com.krypt.app.ui.home.InstalledAppsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-003 / FR-004 Default-Deny engine: every app installed after Krypt is
 * locked, and a "New App Protected" notification is posted.
 *
 * Two entry points, both driven by the accessibility service because it is
 * the one component that stays running:
 *  - [onPackageAdded] for live `ACTION_PACKAGE_ADDED` broadcasts. The
 *    receiver is registered at runtime, since Android 8+ no longer delivers
 *    this broadcast to receivers declared in the manifest.
 *  - [catchUp] when the service connects, for apps installed while it was
 *    not running (after a reboot, a crash, or while it was switched off).
 *
 * Only apps that appear in the Home list are locked (FR-036).
 */
@Singleton
class NewInstallLocker @Inject constructor(
    private val installedApps: InstalledAppsRepository,
    private val lockedAppsRepo: LockedAppsRepository,
    private val notificationHelper: NotificationHelper,
) {

    suspend fun onPackageAdded(packageName: String, replacing: Boolean) {
        // An update of an app that is already installed is not a new install.
        if (replacing) return
        val app = installedApps.find(packageName) ?: return
        lock(app.packageName, app.displayName)
    }

    /**
     * Lock apps installed after Krypt that were missed. An app is skipped if
     * its lock row was written after it was installed: it was already locked,
     * or the Guardian unlocked it on purpose. A reinstall resets the app's
     * install time, so a reinstalled app is locked again.
     */
    suspend fun catchUp() {
        val kryptInstalledAt = installedApps.kryptInstalledAtMs()
        for (app in installedApps.allInstalled()) {
            if (app.firstInstallTimeMs <= kryptInstalledAt) continue
            val existing = lockedAppsRepo.findByPackage(app.packageName)
            if (existing != null && existing.updatedAt >= app.firstInstallTimeMs) continue
            lock(app.packageName, app.displayName)
        }
    }

    private suspend fun lock(packageName: String, displayName: String) {
        lockedAppsRepo.lockNewlyInstalledApp(packageName, displayName)
        notificationHelper.notifyAppLocked(packageName, displayName)
        Log.i(TAG, "locked new install pkg=$packageName")
    }

    private companion object {
        const val TAG = "KryptNewInstall"
    }
}
