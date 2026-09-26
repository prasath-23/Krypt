package com.krypt.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun aDayLongUnlock_fitsInAnInt() {
        assertEquals(AppRowStatus.Unlocked(1440), appRowStatus(isLocked = true, grantRemainingMs = 24 * 60 * 60_000L))
    }
}
