package com.krypt.app.guardian

import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.data.settings.KryptSettings
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.deeplink.ApprovalLinkBuilder
import com.krypt.app.deeplink.RequestParseError
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.deeplink.UnlockRequestParser
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Rate-limit / state-machine tests for [GuardianPinViewModel].
 *
 * Crypto is MOCKED — the real PBKDF2 / HMAC are exercised in
 * [GuardianPinValidatorTest]; here we assert the VM correctly counts wrong
 * attempts, triggers the 60-s lockout after MAX_ATTEMPTS, and clears the
 * counter on success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardianPinViewModelTest {

    private lateinit var parser: UnlockRequestParser
    private lateinit var validator: GuardianPinValidator
    private lateinit var approvalBuilder: ApprovalLinkBuilder
    private lateinit var settings: SettingsRepository
    private lateinit var clock: FakeClock

    private val sampleRequest = UnlockRequest(
        requestId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        targetPackage = "com.example.target",
        salt = ByteArray(UnlockRequest.SALT_BYTES) { it.toByte() },
        pinProof = ByteArray(UnlockRequest.PIN_PROOF_BYTES) { (it * 2).toByte() },
        issuedAt = 1_700_000_000L,
        ttlSeconds = 300L,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        parser = mockk()
        validator = mockk()
        approvalBuilder = mockk()
        settings = mockk()
        clock = FakeClock(nowMs = 1_700_000_000_000L)

        every { parser.parse(any(), any()) } returns Outcome.ok(sampleRequest)
        every { settings.settings } returns flowOf(
            KryptSettings(
                kdfIterations = KdfProvider.MIN_ITERATIONS,
                defaultGrantMinutes = 15,
                onboardingComplete = true,
                lastGuardianPairAtMs = 0L,
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = GuardianPinViewModel(
        parser = parser,
        validator = validator,
        approvalBuilder = approvalBuilder,
        settings = settings,
        clock = clock,
    )

    @Test
    fun parseIncoming_goodUrl_movesToReady() = runTest {
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        val s = vm.state.value
        assertTrue(s is GuardianPinState.Ready)
        assertEquals(GuardianPinViewModel.MAX_ATTEMPTS, (s as GuardianPinState.Ready).attemptsLeft)
    }

    @Test
    fun parseIncoming_badUrl_movesToFatalError() = runTest {
        every { parser.parse(any(), any()) } returns Outcome.err(RequestParseError.BadScheme)
        val vm = newVm()
        vm.parseIncoming("not-a-krypt-url")
        assertTrue(vm.state.value is GuardianPinState.FatalError)
    }

    @Test
    fun correctPin_yieldsApprovedStateWithUrl() = runTest {
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.ok(ByteArray(32) { 0xAA.toByte() })
        every {
            approvalBuilder.build(any<ByteArray>(), eq(sampleRequest), any())
        } returns "krypt://approve?v=1&req=abc"

        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("1234".toCharArray())

        val s = vm.state.value
        assertTrue("got $s", s is GuardianPinState.Approved)
        assertEquals("krypt://approve?v=1&req=abc", (s as GuardianPinState.Approved).approvalUrl)
    }

    @Test
    fun wrongPin_decrementsAttemptsAndLeavesStateReady() = runTest {
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.err(GuardianPinValidator.ValidationError.WrongPin)

        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("0000".toCharArray())

        val s = vm.state.value as GuardianPinState.Ready
        assertEquals(GuardianPinViewModel.MAX_ATTEMPTS - 1, s.attemptsLeft)
        assertTrue(s.errorMessage is ErrorMessage.WrongPin)
    }

    @Test
    fun threeWrongPins_locksOut() = runTest {
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.err(GuardianPinValidator.ValidationError.WrongPin)

        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")

        repeat(3) { vm.approve("0000".toCharArray()) }

        val s = vm.state.value as GuardianPinState.Ready
        assertEquals(0, s.attemptsLeft)
        assertTrue(s.errorMessage is ErrorMessage.Lockout)
    }

    @Test
    fun duringLockout_approveDoesNotCallValidator() = runTest {
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.err(GuardianPinValidator.ValidationError.WrongPin)

        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        repeat(3) { vm.approve("0000".toCharArray()) }
        // Now locked out. 4th attempt must NOT reach validator.
        vm.approve("1234".toCharArray())

        coVerify(exactly = 3) { validator.validate(any(), sampleRequest, any()) }
    }

    @Test
    fun afterLockoutExpiry_canTryAgain() = runTest {
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.err(GuardianPinValidator.ValidationError.WrongPin)

        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        repeat(3) { vm.approve("0000".toCharArray()) }
        // Advance past lockout.
        clock.nowMs += GuardianPinViewModel.LOCKOUT_MS + 1
        // Reset validator to return Ok so we can also verify success clears
        // the counter.
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.ok(ByteArray(32))
        every {
            approvalBuilder.build(any<ByteArray>(), sampleRequest, any())
        } returns "krypt://approve?x"

        vm.approve("1234".toCharArray())
        assertTrue(vm.state.value is GuardianPinState.Approved)
    }

    @Test
    fun successClearsWrongAttemptCounter() = runTest {
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.err(GuardianPinValidator.ValidationError.WrongPin)

        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("0000".toCharArray()) // 1 wrong
        vm.approve("0000".toCharArray()) // 2 wrong

        // Switch to correct.
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.ok(ByteArray(32))
        every {
            approvalBuilder.build(any<ByteArray>(), sampleRequest, any())
        } returns "krypt://approve?x"
        vm.approve("1234".toCharArray())
        assertTrue(vm.state.value is GuardianPinState.Approved)

        // Reset VM-internal mock state for next pair of attempts.
        // Actually, the VM is the same instance; we just assert the counter
        // has been cleared by observing that 3 more wrong attempts are
        // needed to trigger lockout (not 1).
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.err(GuardianPinValidator.ValidationError.WrongPin)
        // Need a fresh Ready state - re-parse the URL.
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("0000".toCharArray())
        val s = vm.state.value as GuardianPinState.Ready
        assertEquals(
            "counter should have been reset after success",
            GuardianPinViewModel.MAX_ATTEMPTS - 1,
            s.attemptsLeft,
        )
    }

    private class FakeClock(var nowMs: Long) : Clock {
        override fun nowMs(): Long = nowMs
    }
}
