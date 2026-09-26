package com.krypt.app.ui.home

import com.krypt.app.common.Clock
import com.krypt.app.data.LockSource
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.security.FakeSharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val installedRepo = mockk<InstalledAppsRepository>(relaxed = true)
    private val lockedRepo = mockk<LockedAppsRepository>(relaxed = true)
    private val iconCache = mockk<AppIconCache>(relaxed = true)

    private val lockedSetFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Guardian unlocks run on the test scheduler's virtual clock. */
    private val clock = object : Clock {
        override fun nowMs() = WALL_START + testDispatcher.scheduler.currentTime
        override fun elapsedMs() = testDispatcher.scheduler.currentTime
    }
    private val sessionStore = LockerSessionStore(FakeSharedPreferences(), clock)

    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { installedRepo.allInstalled() } returns listOf(
            InstalledAppMeta("com.a", "Alpha"),
            InstalledAppMeta("com.b", "Beta"),
            InstalledAppMeta("com.c", "Charlie"),
        )
        every { lockedRepo.allLockedFlow() } returns lockedSetFlow

        vm = HomeViewModel(installedRepo, lockedRepo, sessionStore, iconCache)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The state is only worked out while the screen collects it. */
    private fun TestScope.showHome() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
    }

    private fun row(pkg: String) = vm.state.value.rows.first { it.packageName == pkg }

    private fun unlockFor(pkg: String, minutes: Int) =
        sessionStore.recordGrant(pkg, clock.nowMs() + minutes * 60_000L)

    @Test
    fun initialState_showsAllApps_noneLockedByDefault() = runTest {
        showHome()
        advanceUntilIdle()
        val rows = vm.state.value.rows
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.isLocked })
        assertTrue(rows.all { it.status == AppRowStatus.NotLocked })
    }

    @Test
    fun allLockedFlow_marksCorrectApps() = runTest {
        showHome()
        advanceUntilIdle()
        lockedSetFlow.value = setOf("com.b")
        advanceUntilIdle()

        assertFalse(row("com.a").isLocked)
        assertTrue(row("com.b").isLocked)
        assertEquals(AppRowStatus.Locked, row("com.b").status)
        assertFalse(row("com.c").isLocked)
    }

    @Test
    fun setQuery_filtersRows_caseInsensitive() = runTest {
        showHome()
        advanceUntilIdle()
        vm.setQuery("al")
        advanceUntilIdle()
        val rows = vm.state.value.rows
        assertEquals(1, rows.size)
        assertEquals("Alpha", rows[0].displayName)
    }

    @Test
    fun onToggle_unlocked_callsLockRepository() = runTest {
        showHome()
        advanceUntilIdle()

        vm.onToggle(row("com.a"))
        advanceUntilIdle()

        coVerify(exactly = 1) { lockedRepo.lock("com.a", "Alpha", LockSource.MANUAL) }
    }

    @Test
    fun onToggle_locked_callsUnlockRepository() = runTest {
        lockedSetFlow.value = setOf("com.b")
        showHome()
        advanceUntilIdle()

        vm.onToggle(row("com.b"))
        advanceUntilIdle()

        coVerify(exactly = 1) { lockedRepo.unlock("com.b") }
    }

    @Test
    fun guardianUnlock_showsTheTimeLeft_andTheSwitchStaysOn() = runTest {
        lockedSetFlow.value = setOf("com.b")
        unlockFor("com.b", minutes = 12)
        showHome()

        assertEquals(AppRowStatus.Unlocked(12), row("com.b").status)
        assertTrue("the switch still shows the lock", row("com.b").isLocked)
    }

    @Test
    fun timeLeft_countsDown_thenTheAppShowsLockedAgain() = runTest {
        lockedSetFlow.value = setOf("com.b")
        unlockFor("com.b", minutes = 12)
        showHome()

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(AppRowStatus.Unlocked(11), row("com.b").status)

        advanceTimeBy(11 * 60_000L)
        runCurrent()
        assertEquals(AppRowStatus.Locked, row("com.b").status)
        assertTrue("the ended unlock is cleared, which stops the countdown", sessionStore.grants.value.isEmpty())
    }

    @Test
    fun lockNow_endsTheUnlock() = runTest {
        lockedSetFlow.value = setOf("com.b")
        unlockFor("com.b", minutes = 12)
        showHome()

        vm.onLockNow(row("com.b"))
        runCurrent()

        assertEquals(AppRowStatus.Locked, row("com.b").status)
        assertFalse(sessionStore.isUnlockedNow("com.b"))
    }

    @Test
    fun unlockOnAnAppThatIsNotLocked_showsNotLocked() = runTest {
        unlockFor("com.a", minutes = 12)
        showHome()

        assertEquals(AppRowStatus.NotLocked, row("com.a").status)
    }

    @Test
    fun withoutAnUnlock_nothingTicks() = runTest {
        lockedSetFlow.value = setOf("com.b")
        showHome()

        advanceUntilIdle() // would never return while a countdown ran

        assertEquals(AppRowStatus.Locked, row("com.b").status)
    }

    private companion object {
        const val WALL_START = 1_700_000_000_000L
    }
}
