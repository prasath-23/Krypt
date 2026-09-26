package com.krypt.app.ui.main

import com.krypt.app.data.settings.KryptSettings
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.security.FakeMasterKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val settings = mockk<SettingsRepository> {
        every { settings } returns flowOf(
            KryptSettings(
                kdfIterations = 300_000,
                defaultGrantMinutes = 15,
                onboardingComplete = true,
                lastGuardianPairAtMs = 0L,
            )
        )
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun homeStartsBehindThePin_opensOnSuccess_andClosesWhenKryptIsLeft() = runTest {
        val vm = MainViewModel(settings, FakeMasterKeyStore())
        assertFalse("locked on launch", vm.appEntryUnlocked.value)

        vm.markAppEntryUnlocked()
        assertTrue(vm.appEntryUnlocked.value)

        vm.lockAppEntry()
        assertFalse("locked again after leaving Krypt", vm.appEntryUnlocked.value)
    }

    @Test
    fun masterKeyConfigured_reflectsTheStore() = runTest {
        val store = FakeMasterKeyStore()
        val vm = MainViewModel(settings, store)
        assertFalse(vm.masterKeyConfigured.value)

        store.save(ByteArray(16), ByteArray(32), ByteArray(32))
        vm.refreshMasterKeyConfigured()

        assertTrue(vm.masterKeyConfigured.value)
    }
}
