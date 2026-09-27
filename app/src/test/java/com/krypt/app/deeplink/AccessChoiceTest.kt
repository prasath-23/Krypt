package com.krypt.app.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccessChoiceTest {

    @Test
    fun oneTime_allowsOneMinuteToOneDay() {
        assertEquals(1, AccessChoice.OneTime(1).minutes)
        assertEquals(24 * 60, AccessChoice.OneTime(24 * 60).minutes)
    }

    @Test
    fun everyDay_allowsOneMinuteToOneDay_forOneTo365Days() {
        assertEquals(1, AccessChoice.EveryDay(1, 1).days)
        assertEquals(365, AccessChoice.EveryDay(24 * 60, 365).days)
    }

    @Test
    fun everyDay_rejectsAnythingElse() {
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.EveryDay(0, 7) }
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.EveryDay(24 * 60 + 1, 7) }
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.EveryDay(60, 0) }
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.EveryDay(60, 366) }
    }

    @Test
    fun oneTime_rejectsAnythingElse() {
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.OneTime(0) }
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.OneTime(-5) }
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.OneTime(24 * 60 + 1) }
    }
}
