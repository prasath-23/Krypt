package com.krypt.app.ui.main

import com.krypt.app.common.Outcome
import com.krypt.app.common.TestClock
import com.krypt.app.security.FakeSharedPreferences
import com.krypt.app.security.LocalPinValidator
import com.krypt.app.security.PinAttemptLimiter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppEntryViewModelTest {

    private val validator = mockk<LocalPinValidator>()
    private val clock = TestClock()
    private val limiter = PinAttemptLimiter(FakeSharedPreferences(), clock)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { validator.validate(any()) } returns Outcome.err(LocalPinValidator.ValidationError.WrongPin)
        coEvery { validator.validate(match { String(it) == CORRECT }) } returns Outcome.ok(Unit)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AppEntryViewModel(validator, limiter)

    @Test
    fun correctPin_opensAndResetsTheLimiter() = runTest {
        limiter.beginAttempt()
        val vm = vm()

        vm.submitPin(CORRECT.toCharArray())

        assertEquals(AppEntryState.Success, vm.state.value)
        assertEquals("count reset by the success", 2, limiter.beginAttempt())
    }

    @Test
    fun handledSuccess_doesNotLetTheNextVisitStraightIn() = runTest {
        val vm = vm()
        vm.submitPin(CORRECT.toCharArray())
        assertEquals(AppEntryState.Success, vm.state.value)

        vm.onSuccessHandled()

        // Krypt re-locks; the same (activity-scoped) ViewModel backs the PIN screen again.
        assertEquals(AppEntryState.Idle, vm.state.value)
    }

    @Test
    fun onSuccessHandled_leavesOtherStatesAlone() = runTest {
        val vm = vm()
        vm.submitPin("1111".toCharArray())

        vm.onSuccessHandled()

        assertEquals(AppEntryState.WrongPin(attemptsLeft = 2), vm.state.value)
    }

    @Test
    fun wrongPins_countDownThenLockOut() = runTest {
        val vm = vm()

        vm.submitPin("1111".toCharArray())
        assertEquals(AppEntryState.WrongPin(attemptsLeft = 2), vm.state.value)
        vm.acknowledgeError()
        vm.submitPin("2222".toCharArray())
        assertEquals(AppEntryState.WrongPin(attemptsLeft = 1), vm.state.value)
        vm.acknowledgeError()
        vm.submitPin("3333".toCharArray())

        assertEquals(AppEntryState.LockedOut(retryInMs = 30_000), vm.state.value)
    }

    @Test
    fun leavingKryptMidCheck_stillCountsTheGuess() = runTest {
        // The check never finishes: Krypt was swiped away while it ran.
        coEvery { validator.validate(match { String(it) == "1111" }) } coAnswers { awaitCancellation() }
        val vm = vm()

        vm.submitPin("1111".toCharArray())

        assertEquals(AppEntryState.Validating, vm.state.value)
        assertEquals("the unfinished guess already counted", 1, limiter.beginAttempt())
    }

    @Test
    fun duringLockout_pinIsNotCheckedAndIsWiped() = runTest {
        repeat(3) { limiter.beginAttempt() }
        val vm = vm()
        val pin = CORRECT.toCharArray()

        vm.submitPin(pin)

        coVerify(exactly = 0) { validator.validate(any()) }
        assertTrue(vm.state.value is AppEntryState.LockedOut)
        assertTrue("PIN chars wiped", pin.all { it == ' ' })
    }

    @Test
    fun lockoutFromTheGuardianScreen_carriesOver() {
        repeat(3) { limiter.beginAttempt() }

        assertEquals(AppEntryState.LockedOut(retryInMs = 30_000), vm().state.value)
    }

    @Test
    fun afterTheLockoutRunsOut_pinCanBeTriedAgain() = runTest {
        repeat(3) { limiter.beginAttempt() }
        val vm = vm()

        clock.advance(30_000)
        vm.acknowledgeError()
        assertEquals(AppEntryState.Idle, vm.state.value)

        vm.submitPin(CORRECT.toCharArray())
        assertEquals(AppEntryState.Success, vm.state.value)
    }

    @Test
    fun acknowledgingAnActiveLockout_keepsItLocked() = runTest {
        repeat(3) { limiter.beginAttempt() }
        val vm = vm()

        clock.advance(10_000)
        vm.acknowledgeError()

        assertEquals(AppEntryState.LockedOut(retryInMs = 20_000), vm.state.value)
    }

    @Test
    fun missingPinSetup_isReported_andNeverCountsAsAGuess() = runTest {
        coEvery { validator.validate(any()) } returns
            Outcome.err(LocalPinValidator.ValidationError.NotConfigured)
        val vm = vm()

        repeat(5) {
            vm.submitPin(CORRECT.toCharArray())
            assertEquals(AppEntryState.NotConfigured, vm.state.value)
            vm.acknowledgeError()
        }

        assertEquals("no lockout", 0L, limiter.lockedForMs())
        assertEquals("no attempts charged", 2, limiter.beginAttempt())
    }

    private companion object {
        const val CORRECT = "2468"
    }
}
