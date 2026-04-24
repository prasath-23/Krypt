package com.krypt.app.ui.setup

import app.cash.turbine.test
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.crypto.SecureRandomSource
import com.krypt.app.data.settings.KryptSettings
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.security.FakeMasterKeyStore
import com.krypt.app.security.MasterKeyStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PinSetupViewModelTest {

    private lateinit var kdf: KdfProvider
    private lateinit var rng: SecureRandomSource
    private lateinit var hmac: HmacProvider
    private lateinit var store: FakeMasterKeyStore
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        kdf = mockk(relaxed = false)
        rng = mockk(relaxed = false)
        hmac = HmacProvider() // real — JCE is available in JVM tests, fast
        store = FakeMasterKeyStore()
        settings = mockk(relaxed = false)

        // Default settings flow: calibration not yet run (returns floor).
        every { settings.settings } returns flowOf(
            KryptSettings(
                kdfIterations = KdfProvider.MIN_ITERATIONS,
                defaultGrantMinutes = 15,
                onboardingComplete = true,
                lastGuardianPairAtMs = 0L,
            )
        )
        coEvery { settings.setKdfIterations(any()) } returns Unit

        // Calibration returns a plausible post-floor value.
        every { kdf.calibrateIterationsForDevice(any(), any()) } returns 500_000

        // derive() returns deterministic 32-byte blob distinct enough from a
        // zero-fill that we can detect it in the store.
        every {
            kdf.derive(
                pin = any(),
                salt = any(),
                iterations = any(),
                outputBytes = MasterKeyStore.MASTER_KEY_BYTES,
            )
        } answers {
            ByteArray(MasterKeyStore.MASTER_KEY_BYTES) { i -> (i + 1).toByte() }
        }

        // 16-byte deterministic salt for assertions.
        every { rng.nextBytes(MasterKeyStore.SALT_BYTES) } returns
            ByteArray(MasterKeyStore.SALT_BYTES) { i -> (i * 2).toByte() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = PinSetupViewModel(kdf, rng, hmac, store, settings)

    @Test
    fun validPin_persistsAllThreeFields() = runTest {
        val vm = newVm()
        vm.save("1234".toCharArray(), "1234".toCharArray())

        vm.state.test {
            // Skip Idle / Saving transitions; assert final state is Done.
            var last: PinSetupState = awaitItem()
            while (last !is PinSetupState.Done && last !is PinSetupState.Error) {
                last = awaitItem()
            }
            assertTrue("expected Done, got $last", last is PinSetupState.Done)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, store.saveCallCount)
        assertEquals(MasterKeyStore.SALT_BYTES, store.savedSalt!!.size)
        assertEquals(MasterKeyStore.MASTER_KEY_BYTES, store.savedMasterKey!!.size)
        assertEquals(MasterKeyStore.PIN_PROOF_BYTES, store.savedPinProof!!.size)
    }

    @Test
    fun validPin_zeroesTypedPinCharArray() = runTest {
        val vm = newVm()
        val pin = "1234".toCharArray()
        val confirm = "1234".toCharArray()

        vm.save(pin, confirm)
        waitForTerminal(vm)

        assertArrayEquals(
            "PIN CharArray must be zeroed after derivation",
            CharArray(4) { ' ' },
            pin,
        )
    }

    @Test
    fun validPin_calibratesOnceAndPersistsIterations() = runTest {
        val vm = newVm()
        vm.save("1234".toCharArray(), "1234".toCharArray())
        waitForTerminal(vm)

        val iterSlot = slot<Int>()
        coVerify(exactly = 1) { settings.setKdfIterations(capture(iterSlot)) }
        assertEquals(500_000, iterSlot.captured)
    }

    @Test
    fun cachedIterations_skipsCalibration() = runTest {
        // When settings already reflect a calibrated value, calibrate() MUST
        // NOT be called again.
        every { settings.settings } returns flowOf(
            KryptSettings(
                kdfIterations = 750_000,
                defaultGrantMinutes = 15,
                onboardingComplete = true,
                lastGuardianPairAtMs = 0L,
            )
        )
        val vm = newVm()
        vm.save("1234".toCharArray(), "1234".toCharArray())
        waitForTerminal(vm)

        coVerify(exactly = 0) { settings.setKdfIterations(any()) }
    }

    @Test
    fun mismatchedPin_setsErrorStateAndSkipsStore() = runTest {
        val vm = newVm()
        vm.save("1234".toCharArray(), "1235".toCharArray())
        // Synchronous transition on the main-thread coroutine.
        assertTrue(vm.state.value is PinSetupState.Error)
        assertSame(
            PinSetupError.Mismatch,
            (vm.state.value as PinSetupState.Error).reason,
        )
        assertEquals(0, store.saveCallCount)
    }

    @Test
    fun tooShortPin_setsErrorState() = runTest {
        val vm = newVm()
        vm.save("12".toCharArray(), "12".toCharArray())
        assertTrue(vm.state.value is PinSetupState.Error)
        assertSame(
            PinSetupError.TooShort,
            (vm.state.value as PinSetupState.Error).reason,
        )
        assertEquals(0, store.saveCallCount)
    }

    @Test
    fun nonNumericPin_setsErrorState() = runTest {
        val vm = newVm()
        vm.save("abcd".toCharArray(), "abcd".toCharArray())
        assertTrue(vm.state.value is PinSetupState.Error)
        assertSame(
            PinSetupError.NotNumeric,
            (vm.state.value as PinSetupState.Error).reason,
        )
        assertEquals(0, store.saveCallCount)
    }

    @Test
    fun acknowledgeError_returnsToIdle() = runTest {
        val vm = newVm()
        vm.save("1".toCharArray(), "1".toCharArray())
        assertTrue(vm.state.value is PinSetupState.Error)
        vm.acknowledgeError()
        assertSame(PinSetupState.Idle, vm.state.value)
    }

    private suspend fun waitForTerminal(vm: PinSetupViewModel) {
        vm.state.test {
            var last = awaitItem()
            while (last !is PinSetupState.Done && last !is PinSetupState.Error) {
                last = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
