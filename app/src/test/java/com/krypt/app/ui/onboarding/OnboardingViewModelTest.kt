package com.krypt.app.ui.onboarding

import com.krypt.app.data.settings.KryptSettings
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.permission.FakePermissionStatusProbe
import com.krypt.app.permission.PermissionKey
import com.krypt.app.permission.PermissionStateObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakeProbe = FakePermissionStatusProbe(allGranted = false)
    private val fakeSettings = mockk<SettingsRepository>(relaxed = true)
    private lateinit var observer: PermissionStateObserver
    private lateinit var vm: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val ctx = mockk<android.content.Context>(relaxed = true)
        observer = PermissionStateObserver(fakeProbe, ctx)
        coEvery { fakeSettings.settings } returns flowOf(
            KryptSettings(300_000, 15, false, 0L)
        )
        vm = OnboardingViewModel(observer, fakeSettings)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initialStep_isAccessibility_Mandatory() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(OnboardingStep.Accessibility, state.currentStep)
        assertFalse("canSkip is false on Mandatory step", state.canSkip)
    }

    @Test
    fun next_advancesToNextStep() = runTest {
        vm.next()
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(OnboardingStep.Overlay, state.currentStep)
    }

    @Test
    fun back_doesNotGoBelowZero() = runTest {
        vm.back()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.uiState.value.stepIndex)
    }

    @Test
    fun skip_onMandatoryStep_isNoOp() = runTest {
        val indexBefore = vm.uiState.value.stepIndex
        vm.skip()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(indexBefore, vm.uiState.value.stepIndex)
    }

    @Test
    fun skip_onOptionalStep_advances() = runTest {
        // DeviceAdmin is at index 3 (OPTIONAL)
        repeat(3) { vm.next(); testDispatcher.scheduler.advanceUntilIdle() }
        assertEquals(OnboardingStep.DeviceAdmin, vm.uiState.value.currentStep)
        assertTrue(vm.uiState.value.canSkip)

        vm.skip()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(OnboardingStep.Notifications, vm.uiState.value.currentStep)
    }

    @Test
    fun canFinish_isFalse_whenMandatoryNotAllGranted() = runTest {
        fakeProbe.grant(PermissionKey.ACCESSIBILITY)
        fakeProbe.grant(PermissionKey.OVERLAY)
        observer.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.canFinish)
    }

    @Test
    fun canFinish_isTrue_whenAllMandatoryGranted() = runTest {
        fakeProbe.grant(PermissionKey.ACCESSIBILITY)
        fakeProbe.grant(PermissionKey.OVERLAY)
        fakeProbe.grant(PermissionKey.BATTERY)
        observer.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.canFinish)
    }

    @Test
    fun tryFinish_whenCannotFinish_doesNotCallSettings() = runTest {
        var callbackInvoked = false
        vm.tryFinish { callbackInvoked = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(callbackInvoked)
        coVerify(exactly = 0) { fakeSettings.setOnboardingComplete(any()) }
    }

    @Test
    fun tryFinish_whenCanFinish_callsSettingsAndCallback() = runTest {
        fakeProbe.grant(PermissionKey.ACCESSIBILITY)
        fakeProbe.grant(PermissionKey.OVERLAY)
        fakeProbe.grant(PermissionKey.BATTERY)
        observer.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        var callbackInvoked = false
        vm.tryFinish { callbackInvoked = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(callbackInvoked)
        coVerify(exactly = 1) { fakeSettings.setOnboardingComplete(true) }
    }

    @Test
    fun mandatoryProgress_onlyCountsMandatoryKeys() = runTest {
        // Grant Device Admin (Optional) — should NOT advance mandatory progress
        fakeProbe.grant(PermissionKey.DEVICE_ADMIN)
        observer.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.uiState.value.mandatoryGranted)
    }
}
