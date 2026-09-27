package com.krypt.app.data

import com.krypt.app.data.daily.DailyAccess
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AccessPolicyTest {

    private val lastDay = LocalDate.of(2026, 10, 3)
    private val available = DailyAccess.Available(remainingMs = 60_000, minutesPerDay = 60, lastDay = lastDay)
    private val usedUp = DailyAccess.UsedUp(minutesPerDay = 60, lastDay = lastDay)
    private val paused = DailyAccess.Paused(minutesPerDay = 60, lastDay = lastDay)

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
}
