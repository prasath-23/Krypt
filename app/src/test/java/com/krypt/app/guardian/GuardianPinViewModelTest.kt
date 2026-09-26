package com.krypt.app.guardian

import com.krypt.app.common.Outcome
import com.krypt.app.common.TestClock
import com.krypt.app.deeplink.ApprovalLinkBuilder
import com.krypt.app.deeplink.RequestParseError
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.deeplink.UnlockRequestParser
import com.krypt.app.security.FakeSharedPreferences
import com.krypt.app.security.PinAttemptLimiter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * State-machine and rate-limit tests for [GuardianPinViewModel].
 *
 * Crypto is MOCKED — the real PBKDF2 / HMAC are exercised in
 * [GuardianPinValidatorTest]; here we assert the VM counts every attempt
 * against the shared persistent [PinAttemptLimiter] as soon as its check
 * starts, locks out on the third wrong PIN, and clears the count on success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardianPinViewModelTest {

    private lateinit var parser: UnlockRequestParser
    private lateinit var validator: GuardianPinValidator
    private lateinit var approvalBuilder: ApprovalLinkBuilder
    private val clock = TestClock()
    private val prefs = FakeSharedPreferences()
    private val limiter = PinAttemptLimiter(prefs, clock)

    private val sampleRequest = UnlockRequest(
        requestId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        targetPackage = "com.example.target",
        salt = ByteArray(UnlockRequest.SALT_BYTES) { it.toByte() },
        pinProof = ByteArray(UnlockRequest.PIN_PROOF_BYTES) { (it * 2).toByte() },
        issuedAt = 1_700_000_000L,
        ttlSeconds = 300L,
        kdfIterations = 812_345,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        parser = mockk()
        validator = mockk()
        approvalBuilder = mockk()

        every { parser.parse(any(), any()) } returns Outcome.ok(sampleRequest)
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.err(GuardianPinValidator.ValidationError.WrongPin)
        every { approvalBuilder.build(any<ByteArray>(), sampleRequest, any()) } returns APPROVAL_URL
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = GuardianPinViewModel(
        parser = parser,
        validator = validator,
        approvalBuilder = approvalBuilder,
        limiter = limiter,
        clock = clock,
    )

    private fun pinIsCorrect() {
        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.ok(ByteArray(32) { 0xAA.toByte() })
    }

    private fun readyState(vm: GuardianPinViewModel) = vm.state.value as GuardianPinState.Ready

    @Test
    fun parseIncoming_goodUrl_movesToReady() = runTest {
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        val s = readyState(vm)
        assertEquals(PinAttemptLimiter.FIRST_LOCKOUT_AT, s.attemptsLeft)
        assertNull(s.lockoutRetryInMs)
    }

    @Test
    fun parseIncoming_badUrl_movesToFatalError() = runTest {
        every { parser.parse(any(), any()) } returns Outcome.err(RequestParseError.BadScheme)
        val vm = newVm()
        vm.parseIncoming("not-a-krypt-url")
        assertTrue(vm.state.value is GuardianPinState.FatalError)
    }

    @Test
    fun parseIncoming_badIterationCount_isUnreadable() = runTest {
        every { parser.parse(any(), any()) } returns Outcome.err(RequestParseError.BadKdfIterations)
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        assertEquals(GuardianPinState.FatalError("request_unreadable"), vm.state.value)
    }

    @Test
    fun correctPin_yieldsApprovedStateWithUrl() = runTest {
        pinIsCorrect()
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("1234".toCharArray())

        val s = vm.state.value
        assertTrue("got $s", s is GuardianPinState.Approved)
        assertEquals(APPROVAL_URL, (s as GuardianPinState.Approved).approvalUrl)
    }

    @Test
    fun approve_derivesWithTheRequestsIterationCount() = runTest {
        // The Subject calibrated its own PBKDF2 work factor at PIN setup; the
        // Guardian must use that value, not a local setting, or the right
        // PIN fails.
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("1234".toCharArray())

        coVerify(exactly = 1) { validator.validate(any(), sampleRequest, 812_345) }
    }

    @Test
    fun wrongPin_decrementsAttemptsAndLeavesStateReady() = runTest {
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("0000".toCharArray())

        val s = readyState(vm)
        assertEquals(2, s.attemptsLeft)
        assertEquals(ErrorMessage.WrongPin(attemptsLeft = 2), s.errorMessage)
    }

    @Test
    fun threeWrongPins_lockOut() = runTest {
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")

        repeat(3) { vm.approve("0000".toCharArray()) }

        val s = readyState(vm)
        assertEquals(0, s.attemptsLeft)
        assertEquals(30_000L, s.lockoutRetryInMs)
        assertEquals(ErrorMessage.Lockout(30_000L), s.errorMessage)
    }

    @Test
    fun duringLockout_approveDoesNotCallValidator() = runTest {
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        repeat(3) { vm.approve("0000".toCharArray()) }

        vm.approve("1234".toCharArray())

        coVerify(exactly = 3) { validator.validate(any(), sampleRequest, any()) }
    }

    @Test
    fun reopeningTheScreen_doesNotResetTheLockout() = runTest {
        val first = newVm()
        first.parseIncoming("krypt://request?v=1")
        repeat(3) { first.approve("0000".toCharArray()) }

        val reopened = newVm()
        reopened.parseIncoming("krypt://request?v=1")

        assertEquals(30_000L, readyState(reopened).lockoutRetryInMs)
    }

    @Test
    fun leavingMidCheck_stillCountsTheGuess() = runTest {
        // The check never finishes: the Guardian screen was closed while it ran.
        coEvery { validator.validate(any(), sampleRequest, any()) } coAnswers { awaitCancellation() }
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")

        vm.approve("0000".toCharArray())

        assertTrue(vm.state.value is GuardianPinState.Validating)
        assertEquals("the unfinished guess already counted", 1, limiter.beginAttempt())
    }

    @Test
    fun afterLockoutExpiry_canTryAgain() = runTest {
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        repeat(3) { vm.approve("0000".toCharArray()) }

        clock.advance(30_000)
        vm.lockoutElapsed()
        assertNull(readyState(vm).lockoutRetryInMs)

        pinIsCorrect()
        vm.approve("1234".toCharArray())
        assertTrue(vm.state.value is GuardianPinState.Approved)
    }

    @Test
    fun lockoutElapsedTooEarly_keepsTheScreenLocked() = runTest {
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        repeat(3) { vm.approve("0000".toCharArray()) }

        clock.advance(10_000)
        vm.lockoutElapsed()

        assertEquals(20_000L, readyState(vm).lockoutRetryInMs)
    }

    @Test
    fun successClearsWrongAttemptCounter() = runTest {
        val vm = newVm()
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("0000".toCharArray()) // 1 wrong
        vm.approve("0000".toCharArray()) // 2 wrong

        pinIsCorrect()
        vm.approve("1234".toCharArray())
        assertTrue(vm.state.value is GuardianPinState.Approved)

        coEvery { validator.validate(any(), sampleRequest, any()) } returns
            Outcome.err(GuardianPinValidator.ValidationError.WrongPin)
        vm.parseIncoming("krypt://request?v=1")
        vm.approve("0000".toCharArray())
        assertEquals(
            "counter should have been reset after success",
            2,
            readyState(vm).attemptsLeft,
        )
    }

    private companion object {
        const val APPROVAL_URL = "krypt://approve?v=1&req=abc"
    }
}
