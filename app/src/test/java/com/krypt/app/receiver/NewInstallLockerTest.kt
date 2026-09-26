package com.krypt.app.receiver

import com.krypt.app.data.FakeLockedAppsRepository
import com.krypt.app.data.LockSource
import com.krypt.app.data.LockState
import com.krypt.app.data.LockedApp
import com.krypt.app.notifications.NotificationHelper
import com.krypt.app.ui.home.InstalledAppMeta
import com.krypt.app.ui.home.InstalledAppsRepository
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewInstallLockerTest {

    private val lockedApps = FakeLockedAppsRepository()
    private val notifications = mockk<NotificationHelper>(relaxed = true)
    private val installed = FakeInstalledApps(kryptInstalledAt = KRYPT_INSTALLED_AT)
    private val locker = NewInstallLocker(installed, lockedApps, notifications)

    @Test
    fun newUserApp_isLockedAndAnnounced() = runTest {
        installed.apps += app("com.example.new", "New App", installedAt = KRYPT_INSTALLED_AT + 1)

        locker.onPackageAdded("com.example.new", replacing = false)

        val row = lockedApps.findByPackage("com.example.new")!!
        assertEquals(LockState.LOCKED, row.lockState)
        assertEquals(LockSource.DEFAULT_DENY, row.lockSource)
        verify(exactly = 1) { notifications.notifyAppLocked("com.example.new", "New App") }
    }

    @Test
    fun updateOfAnInstalledApp_isIgnored() = runTest {
        installed.apps += app("com.example.app", "App", installedAt = KRYPT_INSTALLED_AT - 1)

        locker.onPackageAdded("com.example.app", replacing = true)

        assertNull(lockedApps.findByPackage("com.example.app"))
        verify(exactly = 0) { notifications.notifyAppLocked(any(), any()) }
    }

    @Test
    fun systemOrUnknownPackage_isIgnored() = runTest {
        // find() returns null for system apps, launchers and Krypt itself (FR-036).
        locker.onPackageAdded("com.android.systemthing", replacing = false)

        assertNull(lockedApps.findByPackage("com.android.systemthing"))
        verify(exactly = 0) { notifications.notifyAppLocked(any(), any()) }
    }

    @Test
    fun catchUp_locksAppsInstalledAfterKryptThatWereMissed() = runTest {
        installed.apps += app("com.example.missed", "Missed", installedAt = KRYPT_INSTALLED_AT + 60_000)

        locker.catchUp()

        assertEquals(LockState.LOCKED, lockedApps.findByPackage("com.example.missed")?.lockState)
        verify(exactly = 1) { notifications.notifyAppLocked("com.example.missed", "Missed") }
    }

    @Test
    fun catchUp_leavesAppsInstalledBeforeKryptAlone() = runTest {
        installed.apps += app("com.example.old", "Old", installedAt = KRYPT_INSTALLED_AT - 60_000)

        locker.catchUp()

        assertNull(lockedApps.findByPackage("com.example.old"))
    }

    @Test
    fun catchUp_keepsAnAppTheGuardianUnlockedAfterInstall() = runTest {
        val installedAt = KRYPT_INSTALLED_AT + 60_000
        installed.apps += app("com.example.allowed", "Allowed", installedAt)
        lockedApps.seed(row("com.example.allowed", LockState.UNLOCKED, updatedAt = installedAt + 5_000))

        locker.catchUp()

        assertEquals(LockState.UNLOCKED, lockedApps.findByPackage("com.example.allowed")?.lockState)
        verify(exactly = 0) { notifications.notifyAppLocked(any(), any()) }
    }

    @Test
    fun catchUp_relocksAnAppReinstalledSinceItWasUnlocked() = runTest {
        val reinstalledAt = KRYPT_INSTALLED_AT + 10 * 60_000
        installed.apps += app("com.example.again", "Again", reinstalledAt)
        lockedApps.seed(row("com.example.again", LockState.UNLOCKED, updatedAt = reinstalledAt - 60_000))

        locker.catchUp()

        assertEquals(LockState.LOCKED, lockedApps.findByPackage("com.example.again")?.lockState)
    }

    @Test
    fun catchUp_doesNotAnnounceAppsAlreadyLocked() = runTest {
        val installedAt = KRYPT_INSTALLED_AT + 60_000
        installed.apps += app("com.example.locked", "Locked", installedAt)
        lockedApps.seed(row("com.example.locked", LockState.LOCKED, updatedAt = installedAt + 1))

        locker.catchUp()

        verify(exactly = 0) { notifications.notifyAppLocked(any(), any()) }
    }

    private fun app(pkg: String, name: String, installedAt: Long) =
        InstalledAppMeta(pkg, name, firstInstallTimeMs = installedAt)

    private fun row(pkg: String, state: LockState, updatedAt: Long) = LockedApp(
        packageName = pkg,
        displayName = pkg,
        lockState = state,
        lockSource = LockSource.DEFAULT_DENY,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    private class FakeInstalledApps(private val kryptInstalledAt: Long) : InstalledAppsRepository {
        val apps = mutableListOf<InstalledAppMeta>()
        override suspend fun allInstalled(): List<InstalledAppMeta> = apps.toList()
        override suspend fun find(packageName: String): InstalledAppMeta? =
            apps.firstOrNull { it.packageName == packageName }
        override suspend fun kryptInstalledAtMs(): Long = kryptInstalledAt
    }

    private companion object {
        const val KRYPT_INSTALLED_AT = 1_700_000_000_000L
    }
}
