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
    fun everyDay_inPeriods_takesAnyPeriodFromFiveMinutesToADay_andUpToTheWholePeriod() {
        assertEquals(120, AccessChoice.EveryDay(30, 7, periodMinutes = 120).periodMinutes)
        assertEquals(30, AccessChoice.EveryDay(30, 7, periodMinutes = 30).minutes)
        assertEquals(90, AccessChoice.EveryDay(20, 7, periodMinutes = 90).periodMinutes)
        assertEquals(5, AccessChoice.EveryDay(1, 7, periodMinutes = 5).periodMinutes)
        assertEquals(300, AccessChoice.EveryDay(60, 7, periodMinutes = 300).periodMinutes)
        assertEquals("a day unless said", 24 * 60, AccessChoice.EveryDay(30, 7).periodMinutes)
    }

    @Test
    fun everyDay_inPeriods_rejectsMoreThanThePeriod_andPeriodsOutsideFiveMinutesToADay() {
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.EveryDay(31, 7, periodMinutes = 30) }
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.EveryDay(91, 7, periodMinutes = 90) }
        for (minutes in listOf(-30, 0, 4, 24 * 60 + 1, 48 * 60)) {
            assertThrows("$minutes minutes", IllegalArgumentException::class.java) {
                AccessChoice.EveryDay(1, 7, periodMinutes = minutes)
            }
        }
    }

    @Test
    fun oneTime_rejectsAnythingElse() {
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.OneTime(0) }
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.OneTime(-5) }
        assertThrows(IllegalArgumentException::class.java) { AccessChoice.OneTime(24 * 60 + 1) }
    }
}
