package com.krypt.app.ui.home

import android.content.Intent
import com.krypt.app.data.LockSource
import com.krypt.app.data.LockedApp
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.data.LockSource as LS
import com.krypt.app.data.LockState
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.deeplink.UnlockRequestBuilder
import com.krypt.app.security.MasterKeyStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val installedRepo = mockk<InstalledAppsRepository>(relaxed = true)
    private val lockedRepo = mockk<LockedAppsRepository>(relaxed = true)
    private val requestBuilder = mockk<UnlockRequestBuilder>(relaxed = true)
    private val masterKeyStore = mockk<MasterKeyStore>(relaxed = true)
    private val iconCache = mockk<AppIconCache>(relaxed = true)

    private val lockedSetFlow = MutableStateFlow<Set<String>>(emptySet())

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

        vm = HomeViewModel(installedRepo, lockedRepo, requestBuilder, masterKeyStore, iconCache)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initialState_showsAllApps_noneLockedByDefault() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val rows = vm.state.value.rows
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.isLocked })
    }

    @Test
    fun allLockedFlow_marksCorrectApps() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        lockedSetFlow.value = setOf("com.b")
        testDispatcher.scheduler.advanceUntilIdle()

        val rows = vm.state.value.rows
        assertFalse(rows.first { it.packageName == "com.a" }.isLocked)
        assertTrue(rows.first { it.packageName == "com.b" }.isLocked)
        assertFalse(rows.first { it.packageName == "com.c" }.isLocked)
    }

    @Test
    fun setQuery_filtersRows_caseInsensitive() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setQuery("al")
        testDispatcher.scheduler.advanceUntilIdle()
        val rows = vm.state.value.rows
        assertEquals(1, rows.size)
        assertEquals("Alpha", rows[0].displayName)
    }

    @Test
    fun onToggle_unlocked_callsLockRepository() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val row = vm.state.value.rows.first { it.packageName == "com.a" }

        vm.onToggle(row)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { lockedRepo.lock("com.a", "Alpha", LockSource.MANUAL) }
    }

    @Test
    fun onToggle_locked_doesNotCallLock_emitsShareIntent() = runTest {
        lockedSetFlow.value = setOf("com.b")
        testDispatcher.scheduler.advanceUntilIdle()
        val row = vm.state.value.rows.first { it.packageName == "com.b" }

        val salt = ByteArray(16) { 1 }
        val pinProof = ByteArray(32) { 2 }
        coEvery { masterKeyStore.loadSalt() } returns salt
        coEvery { masterKeyStore.loadPinProof() } returns pinProof

        val url = "krypt://request?pkg=com.b"
        val fakeRequest = mockk<UnlockRequest>(relaxed = true)
        every { requestBuilder.build(salt, pinProof, "com.b", any(), any()) } returns (url to fakeRequest)

        vm.onToggle(row)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { lockedRepo.lock(any(), any(), any()) }

        val intent = vm.shareIntents.tryReceive().getOrNull()
        assertEquals(url, intent?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun onToggle_locked_masterKeyMissing_emitsUserMessage() = runTest {
        lockedSetFlow.value = setOf("com.b")
        testDispatcher.scheduler.advanceUntilIdle()
        val row = vm.state.value.rows.first { it.packageName == "com.b" }

        coEvery { masterKeyStore.loadSalt() } returns null
        coEvery { masterKeyStore.loadPinProof() } returns null

        vm.onToggle(row)
        testDispatcher.scheduler.advanceUntilIdle()

        val msg = vm.userMessages.tryReceive().getOrNull()
        assertTrue(msg is UserMessage.MasterKeyMissing)
        coVerify(exactly = 0) { requestBuilder.build(any(), any(), any(), any(), any()) }
    }
}
