package com.krypt.app.data

import com.krypt.app.data.daily.DailyAccess
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AccessPolicyTest {

    private val lastDay = LocalDate.of(2026, 10, 3)
    private val available = DailyAccess.Available(remainingMs = 60_000, minutes = 60, lastDay = lastDay)
    private val usedUp = DailyAccess.UsedUp(minutes = 60, lastDay = lastDay)
    private val paused = DailyAccess.Paused(minutes = 60, lastDay = lastDay)

    @Test
    fun anAppThatIsNotLocked_isAlwaysAllowed() {
        for (daily in listOf(DailyAccess.None, available, usedUp, paused)) {
            assertEquals(AccessDecision.Allow, AccessPolicy.decide(isLocked = false, hasGrant = false, daily = daily))
        }
    }

    @Test
    fun aOneTimeUnlock_allowsALockedApp_whateverItsDailyTime() {
        for (daily in listOf(DailyAccess.None, available, usedUp, paused)) {
            assertEquals(AccessDecision.Allow, AccessPolicy.decide(isLocked = true, hasGrant = true, daily = daily))
        }
    }

    @Test
    fun dailyTimeLeft_allowsALockedApp() {
        assertEquals(AccessDecision.Allow, AccessPolicy.decide(isLocked = true, hasGrant = false, daily = available))
    }

    @Test
    fun aLockedAppWithoutTimeLeft_isBlocked_withTheReason() {
        assertEquals(
            AccessDecision.Block(LockReason.Locked),
            AccessPolicy.decide(isLocked = true, hasGrant = false, daily = DailyAccess.None),
        )
        assertEquals(
            AccessDecision.Block(LockReason.DailyUsedUp(60, lastDay)),
            AccessPolicy.decide(isLocked = true, hasGrant = false, daily = usedUp),
        )
        assertEquals(
            AccessDecision.Block(LockReason.DailyPaused(60, lastDay)),
            AccessPolicy.decide(isLocked = true, hasGrant = false, daily = paused),
        )
    }

    @Test
    fun timeEveryFewHours_isBlocked_withWhenItComesBack() {
        val backAtMs = 1_790_000_000_000L
        val usedUpThisHour = DailyAccess.UsedUp(minutes = 15, lastDay = lastDay, periodMinutes = 60, backAtMs = backAtMs)

        assertEquals(
            AccessDecision.Block(LockReason.DailyUsedUp(15, lastDay, periodMinutes = 60, backAtMs = backAtMs)),
            AccessPolicy.decide(isLocked = true, hasGrant = false, daily = usedUpThisHour),
        )
        assertEquals(
            AccessDecision.Block(LockReason.DailyPaused(15, lastDay, periodMinutes = 120)),
            AccessPolicy.decide(isLocked = true, hasGrant = false, daily = DailyAccess.Paused(15, lastDay, periodMinutes = 120)),
        )
    }
}
