package com.krypt.app.data

import com.krypt.app.common.TestClock
import com.krypt.app.security.FakeSharedPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLockedAppsRepositoryTest {

    private val clock = TestClock()
    private val appDao = mockk<LockedAppDao>(relaxed = true)
    private val prefs = FakeSharedPreferences()
    private val sessionStore = LockerSessionStore(prefs, clock)
    private val repo = RoomLockedAppsRepository(appDao, sessionStore, clock)

    private fun grant(pkg: String) = sessionStore.recordGrant(pkg, clock.wallMs + 10 * 60_000)

    /** The store as Krypt's next process would load it. */
    private fun restartedStore() = LockerSessionStore(prefs, clock).apply { restore() }

    @Test
    fun lockingANewInstall_endsAnyUnlockGrant() = runTest {
        grant("com.example.app")

        repo.lockNewlyInstalledApp("com.example.app", "App")

        assertFalse(sessionStore.isUnlockedNow("com.example.app"))
        assertFalse("grant back after a restart", restartedStore().isUnlockedNow("com.example.app"))
    }

    @Test
    fun manualLock_endsAnyUnlockGrant() = runTest {
        coEvery { appDao.findByPackage("com.example.app") } returns null
        grant("com.example.app")

        repo.lock("com.example.app", "App", LockSource.MANUAL)

        assertFalse(sessionStore.isUnlockedNow("com.example.app"))
        assertFalse("grant back after a restart", restartedStore().isUnlockedNow("com.example.app"))
    }

    @Test
    fun lockingAnAlreadyLockedApp_leavesItsGrantAlone() = runTest {
        coEvery { appDao.findByPackage("com.example.app") } returns LockedAppEntity(
            packageName = "com.example.app",
            displayName = "App",
            lockState = LockState.LOCKED,
            lockSource = LockSource.MANUAL,
            createdAt = 1L,
            updatedAt = 1L,
        )
        grant("com.example.app")

        repo.lock("com.example.app", "App", LockSource.MANUAL)

        assertTrue(sessionStore.isUnlockedNow("com.example.app"))
    }

    @Test
    fun lockingOneApp_leavesOtherGrantsAlone() = runTest {
        grant("com.example.other")

        repo.lockNewlyInstalledApp("com.example.app", "App")

        assertTrue(sessionStore.isUnlockedNow("com.example.other"))
    }
}
