package com.krypt.app.data.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Where an every-day rule's periods start and end, and the minutes of the clock it counts in. */
class DailyAllowancePeriodTest {

    private fun rule(periodMinutes: Int, zone: ZoneId = UTC, first: LocalDate = DAY) =
        DailyAllowance("com.example.app", 5, first, first.plusDays(6), zone, UUID.randomUUID(), 0L, periodMinutes)

    private fun utc(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(UTC).toInstant().toEpochMilli()

    @Test
    fun periodsFollowTheClockFromMidnight() {
        val everyTwoHours = rule(periodMinutes = 120)

        assertEquals(utc(DAY, 8), everyTwoHours.periodStartMs(utc(DAY, 9, 30)))
        assertEquals(utc(DAY, 10), everyTwoHours.periodEndMs(utc(DAY, 9, 30)))
        assertEquals(utc(DAY, 10), everyTwoHours.periodStartMs(utc(DAY, 10)))
        val halfPastNine = LocalMinutes.of(utc(DAY, 9, 30), UTC)
        assertEquals("08:00-10:00", (halfPastNine - 90) until (halfPastNine + 30), everyTwoHours.periodOf(halfPastNine))
    }

    @Test
    fun anyPeriodLength_startsFromMidnight() {
        // Every 45 minutes: ..., 07:30-08:15, 08:15-09:00, ...
        assertEquals(utc(DAY, 7, 30), rule(periodMinutes = 45).periodStartMs(utc(DAY, 8)))
        assertEquals(utc(DAY, 8, 15), rule(periodMinutes = 45).periodEndMs(utc(DAY, 8)))
        // Every 90 minutes: ..., 07:30-09:00, ...
        assertEquals(utc(DAY, 9), rule(periodMinutes = 90).periodEndMs(utc(DAY, 8)))
        // Every 30 minutes.
        assertEquals(utc(DAY, 8, 30), rule(periodMinutes = 30).periodEndMs(utc(DAY, 8, 10)))
    }

    @Test
    fun whenThePeriodDoesntDivideADay_theDaysLastPeriodEndsAtMidnight() {
        // Every 5 hours: 00-05, 05-10, 10-15, 15-20, then 20-24.
        val everyFiveHours = rule(periodMinutes = 300)

        assertEquals(utc(DAY, 20), everyFiveHours.periodStartMs(utc(DAY, 21)))
        assertEquals(utc(DAY.plusDays(1), 0), everyFiveHours.periodEndMs(utc(DAY, 21)))
        assertEquals("four hours long", 240, everyFiveHours.periodOf(LocalMinutes.of(utc(DAY, 21), UTC)).count())
        assertEquals("and the next day starts afresh", utc(DAY.plusDays(1), 5), everyFiveHours.periodEndMs(utc(DAY.plusDays(1), 1)))
    }

    @Test
    fun aDaysPeriod_isTheCalendarDay() {
        val aDay = rule(periodMinutes = DailyAllowance.DAY_MINUTES)

        assertEquals(utc(DAY, 0), aDay.periodStartMs(utc(DAY, 13)))
        assertEquals(utc(DAY.plusDays(1), 0), aDay.periodEndMs(utc(DAY, 13)))
        assertEquals(24 * 60, aDay.periodOf(LocalMinutes.of(utc(DAY, 13), UTC)).count())
    }

    @Test
    fun minutesOfTheClock_areCountedInTheRulesZone() {
        assertEquals(DAY.toEpochDay() * 1440 + 9 * 60 + 30, LocalMinutes.of(utc(DAY, 9, 30), UTC))
        // 09:30 UTC is 15:00 in Kolkata.
        assertEquals(DAY.toEpochDay() * 1440 + 15 * 60, LocalMinutes.of(utc(DAY, 9, 30), KOLKATA))
        assertEquals(utc(DAY, 9, 31), LocalMinutes.nextStartMs(utc(DAY, 9, 30) + 30_000, UTC))
        assertEquals(utc(DAY, 10, 30), rule(periodMinutes = 60, zone = KOLKATA).periodEndMs(utc(DAY, 9, 30)))
        assertEquals(DAY, LocalMinutes.day(DAY.toEpochDay() * 1440 + 1439))
    }

    @Test
    fun whenSummerTimeStarts_thePeriodBeforeTheJump_endsAtThreeOClock() {
        val day = LocalDate.of(2026, 3, 8) // New York: 02:00 EST becomes 03:00 EDT
        val halfPastOne = utc(day, 6, 30) // 01:30 EST

        assertEquals(utc(day, 7), rule(periodMinutes = 60, zone = NEW_YORK, first = day).periodEndMs(halfPastOne))
        assertEquals("the minute before the jump ends at it", utc(day, 7), LocalMinutes.nextStartMs(utc(day, 6, 59), NEW_YORK))
        assertEquals("03:00 EDT", day.toEpochDay() * 1440 + 180, LocalMinutes.of(utc(day, 7), NEW_YORK))
    }

    @Test
    fun whenSummerTimeEnds_theRepeatedHourIsOneHour() {
        val day = LocalDate.of(2026, 11, 1) // New York: 02:00 EDT becomes 01:00 EST
        val firstHalfPastOne = utc(day, 5, 30) // 01:30 EDT
        val secondHalfPastOne = utc(day, 6, 30) // 01:30 EST

        assertEquals(LocalMinutes.of(firstHalfPastOne, NEW_YORK), LocalMinutes.of(secondHalfPastOne, NEW_YORK))
        assertEquals(
            "02:00 EST, after both",
            utc(day, 7),
            rule(periodMinutes = 60, zone = NEW_YORK, first = day).periodEndMs(firstHalfPastOne),
        )
    }

    /**
     * Around every clock change of 2026, in zones that move by an hour, by half
     * an hour (Lord Howe), at a quarter past (Chatham) and by two hours (Troll):
     * the next minute starts after now, and the clock stays in the same minute
     * until then; every period ends after now and started by now.
     */
    @Test(timeout = 60_000)
    fun aroundEveryClockChange_minutesAndPeriodsAlwaysMoveForward() {
        for (zone in listOf(LORD_HOWE, CHATHAM, TROLL, NEW_YORK, LONDON)) {
            var change = zone.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
            while (change != null && change.instant.isBefore(Instant.parse("2027-01-01T00:00:00Z"))) {
                val at = change.instant.toEpochMilli()
                checkMovesForward(zone, from = at - 3 * HOUR, until = at + 3 * HOUR)
                change = zone.rules.nextTransition(change.instant)
            }
        }
        checkMovesForward(KOLKATA, from = utc(DAY, 0), until = utc(DAY.plusDays(1), 0))
    }

    private fun checkMovesForward(zone: ZoneId, from: Long, until: Long) {
        val rules = listOf(5, 30, 45, 60, 90, 120, 300, 420, 720, DailyAllowance.DAY_MINUTES)
            .map { rule(periodMinutes = it, zone = zone) }
        var t = from
        while (t < until) {
            val next = LocalMinutes.nextStartMs(t, zone)
            assertTrue("$zone at ${Instant.ofEpochMilli(t)}: the next minute, $next, isn't after it", next > t)
            assertEquals(
                "$zone at ${Instant.ofEpochMilli(t)}: the clock's minute changed before ${Instant.ofEpochMilli(next)}",
                LocalMinutes.of(t, zone),
                LocalMinutes.of(next - 1, zone),
            )
            for (r in rules) {
                assertTrue("$zone, every ${r.periodMinutes} min, at ${Instant.ofEpochMilli(t)}: ends", r.periodEndMs(t) > t)
                assertTrue("$zone, every ${r.periodMinutes} min, at ${Instant.ofEpochMilli(t)}: started", r.periodStartMs(t) <= t)
            }
            t += 7 * MINUTE + 13_000
        }
    }

    @Test
    fun whenLordHowesClocksGoBackHalfAnHour_theRepeatedHalfHourStaysInItsPeriod() {
        val back = LORD_HOWE.rules.nextTransition(Instant.parse("2026-03-01T00:00:00Z")).instant.toEpochMilli()
        // 02:00 (+11:00) becomes 01:30 (+10:30): 01:30-02:00 happens twice.
        val inTheRepeat = back + 15 * MINUTE // 01:45, the second time

        assertEquals(LocalMinutes.of(back - 15 * MINUTE, LORD_HOWE), LocalMinutes.of(inTheRepeat, LORD_HOWE))
        assertEquals("02:00 (+10:30)", back + 30 * MINUTE, rule(periodMinutes = 30, zone = LORD_HOWE).periodEndMs(inTheRepeat))
    }

    @Test
    fun whenTrollsClocksGoBackTwoHours_thePeriodEndsAfterNow() {
        val back = TROLL.rules.nextTransition(Instant.parse("2026-09-01T00:00:00Z")).instant.toEpochMilli()
        // 03:00 (+02:00) becomes 01:00 (+00:00). Half an hour later it is 01:30 again.
        val halfPastOneAgain = back + 30 * MINUTE

        assertEquals("02:00 (+00:00)", back + HOUR, rule(periodMinutes = 60, zone = TROLL).periodEndMs(halfPastOneAgain))
    }

    @Test
    fun whenTheClockJumpsBackIntoAnEarlierPeriod_thePeriodEndsAtTheJump() {
        val back = TROLL.rules.nextTransition(Instant.parse("2026-09-01T00:00:00Z")).instant.toEpochMilli()
        // Every 2 hours, at 02:30 (+02:00): the clock goes back to 01:00, in the 00:00-02:00 period.
        assertEquals(back, rule(periodMinutes = 120, zone = TROLL).periodEndMs(back - 30 * MINUTE))
    }

    @Test
    fun whenTheClockJumpsPastAPeriodsEnd_thePeriodEndsAtTheJump() {
        val forward = CHATHAM.rules.nextTransition(Instant.parse("2026-09-01T00:00:00Z")).instant.toEpochMilli()
        // Every hour, at 02:30 (+12:45): at 02:45 the clock jumps to 03:45, in the next hour.
        assertEquals(forward, rule(periodMinutes = 60, zone = CHATHAM).periodEndMs(forward - 15 * MINUTE))
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
        val LONDON: ZoneId = ZoneId.of("Europe/London")
        val LORD_HOWE: ZoneId = ZoneId.of("Australia/Lord_Howe")
        val CHATHAM: ZoneId = ZoneId.of("Pacific/Chatham")
        val TROLL: ZoneId = ZoneId.of("Antarctica/Troll")
        val DAY: LocalDate = LocalDate.of(2026, 3, 1)
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
