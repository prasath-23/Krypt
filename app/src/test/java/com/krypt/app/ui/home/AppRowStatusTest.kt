package com.krypt.app.ui.home

import com.krypt.app.data.daily.DailyAccess
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AppRowStatusTest {

    @Test
    fun notLockedApp_isNotLocked_evenWithALeftoverUnlock() {
        assertEquals(AppRowStatus.NotLocked, appRowStatus(isLocked = false, grantRemainingMs = 0))
        assertEquals(AppRowStatus.NotLocked, appRowStatus(isLocked = false, grantRemainingMs = 60_000))
    }

    @Test
    fun lockedApp_withoutAnUnlock_isLocked() {
        assertEquals(AppRowStatus.Locked, appRowStatus(isLocked = true, grantRemainingMs = 0))
    }

    @Test
    fun unlockedApp_showsWholeMinutesLeft_roundedUp() {
        assertEquals(AppRowStatus.Unlocked(15), appRowStatus(isLocked = true, grantRemainingMs = 15 * 60_000))
        assertEquals(AppRowStatus.Unlocked(12), appRowStatus(isLocked = true, grantRemainingMs = 11 * 60_000 + 1))
        assertEquals(AppRowStatus.Unlocked(1), appRowStatus(isLocked = true, grantRemainingMs = 1))
    }

    @Test
    fun everyDayTime_showsWhatIsLeftToday_roundedUp() {
        val daily = DailyAccess.Available(remainingMs = 17 * 60_000L + 1, minutesPerDay = 60, lastDay = LAST_DAY)

        assertEquals(
            AppRowStatus.Daily(DailyLine.Left(18, 60, LAST_DAY)),
            appRowStatus(isLocked = true, grantRemainingMs = 0, daily = daily),
        )
    }

    @Test
    fun everyDayTime_usedUpAndPaused() {
        assertEquals(
            AppRowStatus.Daily(DailyLine.UsedUp(60, LAST_DAY)),
            appRowStatus(isLocked = true, grantRemainingMs = 0, daily = DailyAccess.UsedUp(60, LAST_DAY)),
        )
        assertEquals(
            AppRowStatus.Daily(DailyLine.Paused(60, LAST_DAY)),
            appRowStatus(isLocked = true, grantRemainingMs = 0, daily = DailyAccess.Paused(60, LAST_DAY)),
        )
    }

    @Test
    fun aOneTimeUnlock_showsOverEveryDayTime_andAnUnlockedAppShowsNeither() {
        val daily = DailyAccess.UsedUp(60, LAST_DAY)
        assertEquals(AppRowStatus.Unlocked(3), appRowStatus(isLocked = true, grantRemainingMs = 3 * 60_000L, daily = daily))
        assertEquals(AppRowStatus.NotLocked, appRowStatus(isLocked = false, grantRemainingMs = 0, daily = daily))
    }

    @Test
    fun aDayLongUnlock_fitsInAnInt() {
        assertEquals(AppRowStatus.Unlocked(1440), appRowStatus(isLocked = true, grantRemainingMs = 24 * 60 * 60_000L))
    }

    private companion object {
        val LAST_DAY: LocalDate = LocalDate.of(2026, 10, 3)
    }
}
