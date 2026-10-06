package com.krypt.app.security

import com.krypt.app.common.TestClock
import org.junit.Assert.assertEquals
import org.junit.Test

class PinAttemptLimiterTest {

    private val clock = TestClock()
    private val prefs = FakeSharedPreferences()

    private fun limiter() = PinAttemptLimiter(prefs, clock)

    @Test
    fun firstTwoWrongPinsDoNotLock() {
        val limiter = limiter()

        assertEquals(2, limiter.beginAttempt())
        assertEquals(0L, limiter.lockedForMs())
        assertEquals(1, limiter.beginAttempt())
        assertEquals(0L, limiter.lockedForMs())
    }

    @Test
    fun thirdWrongPinLocksForThirtySeconds() {
        val limiter = limiter()
        repeat(2) { limiter.beginAttempt() }

        assertEquals(0, limiter.beginAttempt())
        assertEquals(30_000L, limiter.lockedForMs())
        clock.advance(29_999)
        assertEquals(1L, limiter.lockedForMs())
        clock.advance(1)
        assertEquals(0L, limiter.lockedForMs())
    }

    @Test
    fun lockoutsEscalate() {
        val limiter = limiter()
        val expected = listOf(0L, 0L, 30_000L, 120_000L, 120_000L, 600_000L, 600_000L, 3_600_000L, 3_600_000L)

        expected.forEachIndexed { i, lockMs ->
            limiter.beginAttempt()
            assertEquals("lockout after wrong PIN #${i + 1}", lockMs, limiter.lockedForMs())
            clock.advance(lockMs) // sit the lockout out before the next try
        }
    }

    @Test
    fun correctPinResetsCountAndLockout() {
        val limiter = limiter()
        repeat(3) { limiter.beginAttempt() }

        limiter.recordSuccess()

        assertEquals(0L, limiter.lockedForMs())
        assertEquals("a fresh run of attempts", 2, limiter.beginAttempt())
    }

    @Test
    fun cancelAttempt_takesBackTheCountAndTheLockoutItStarted() {
        val limiter = limiter()
        repeat(2) { limiter.beginAttempt() }
        limiter.beginAttempt() // would be the 3rd wrong PIN: starts a lockout

        limiter.cancelAttempt()

        assertEquals(0L, limiter().lockedForMs())
        assertEquals("back to two counted", 0, limiter().beginAttempt())
    }

    @Test
    fun cancelAttempt_withNothingCounted_changesNothing() {
        limiter().cancelAttempt()

        assertEquals(2, limiter().beginAttempt())
    }

    @Test
    fun stateSurvivesARestart() {
        repeat(3) { limiter().beginAttempt() }

        assertEquals(30_000L, limiter().lockedForMs())
    }

    @Test
    fun movingTheWallClockForwardDoesNotEndALockout() {
        val limiter = limiter()
        repeat(3) { limiter.beginAttempt() }

        clock.wallMs += 60 * 60_000L

        assertEquals(30_000L, limiter.lockedForMs())
    }

    @Test
    fun aRebootStartsARunningLockoutOver() {
        repeat(3) { limiter().beginAttempt() }
        clock.advance(10_000)

        clock.reboot()

        assertEquals(30_000L, limiter().lockedForMs())
        clock.advance(30_000)
        assertEquals(0L, limiter().lockedForMs())
    }

    @Test
    fun aRebootThenMovingTheClockForward_doesNotEndALockout() {
        repeat(3) { limiter().beginAttempt() }

        clock.reboot()
        clock.wallMs += 24 * 60 * 60_000L

        assertEquals(30_000L, limiter().lockedForMs())
    }

    @Test
    fun aServedLockoutDoesNotComeBackAfterAReboot_butTheCountStays() {
        val limiter = limiter()
        repeat(3) { limiter.beginAttempt() }
        clock.advance(30_000)
        assertEquals(0L, limiter.lockedForMs())

        clock.reboot()

        assertEquals(0L, limiter().lockedForMs())
        limiter().beginAttempt() // the 4th wrong PIN still escalates
        assertEquals(120_000L, limiter().lockedForMs())
    }
}
