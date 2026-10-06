package com.krypt.app.common

import com.krypt.app.security.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TrustedDayClockTest {

    private val clock = TestClock()
    private var autoTime = true
    private val prefs = FakeSharedPreferences()

    /** A new instance is a new Krypt process; [prefs] survive like the real ones. */
    private fun dayClock() = TrustedDayClock(prefs, clock) { autoTime }

    @Test
    fun withAutomaticTimeOff_nothingIsTrusted() {
        autoTime = false

        assertNull(dayClock().nowMs())
        assertNull(dayClock().today(UTC))
        assertNull(dayClock().msUntilNextDay(UTC))
    }

    @Test
    fun trustedTime_runsOnTheMonotonicClock() {
        val day = dayClock()
        val start = day.nowMs()!!

        clock.elapsed += 60_000
        clock.wallMs += 3 * DAY // the wall clock alone moving changes nothing

        assertEquals(start + 60_000, day.nowMs())
    }

    @Test
    fun aDateChangedByHand_isIgnored_evenAfterAutomaticTimeIsBackOn() {
        val day = dayClock()
        val start = day.nowMs()!!

        autoTime = false
        day.onAutoTimeChanged()
        clock.wallMs += 3 * DAY
        day.onTimeSet()
        autoTime = true
        day.onAutoTimeChanged()
        clock.elapsed += 20_000

        assertEquals(start + 20_000, day.nowMs())
    }

    @Test
    fun theBroadcastForAChangeByHand_arrivingJustAfterAutomaticTimeIsBackOn_isIgnored() {
        val day = dayClock()
        val start = day.nowMs()!!

        autoTime = false
        day.onAutoTimeChanged()
        clock.wallMs += 3 * DAY
        autoTime = true
        day.onAutoTimeChanged()
        day.onTimeSet() // late

        assertEquals(start, day.nowMs())
    }

    @Test
    fun aNetworkCorrection_isFollowed_evenBackwards() {
        val day = dayClock()
        day.nowMs()
        clock.elapsed += 60_000
        clock.wallMs -= 2 * HOUR // the network says the device was two hours fast

        day.onTimeSet()

        assertEquals(clock.wallMs, day.nowMs())
    }

    @Test
    fun aRestartInTheSameBoot_keepsCountingFromTheSameAnchor() {
        val start = dayClock().nowMs()!!

        clock.wallMs += 3 * DAY // set while Krypt wasn't running
        clock.elapsed += 10_000

        assertEquals(start + 10_000, dayClock().nowMs())
    }

    @Test
    fun afterAReboot_theDayNeverGoesBackwards() {
        val day = dayClock()
        day.nowMs()
        clock.advance(HOUR)
        day.checkpoint()
        val trusted = day.nowMs()!!

        clock.reboot()
        clock.wallMs = trusted - 3 * DAY // set back before rebooting

        assertEquals(trusted, dayClock().nowMs())
    }

    @Test
    fun afterAReboot_aClockAhead_isFollowed_theDocumentedResidualRisk() {
        dayClock().nowMs()

        clock.reboot()
        clock.wallMs += 3 * DAY

        assertEquals(clock.wallMs, dayClock().nowMs())
    }

    @Test
    fun withoutABootCount_aRestartKeepsTheAnchor() {
        clock.boot = null
        val start = dayClock().nowMs()!!

        clock.wallMs += 3 * DAY // set forward while Krypt wasn't running
        clock.elapsed += 10_000

        assertEquals(start + 10_000, dayClock().nowMs())
    }

    @Test
    fun withoutABootCount_aRebootIsToldByTheMonotonicClock_andTheDayNeverGoesBack() {
        clock.boot = null
        val day = dayClock()
        day.nowMs()
        clock.advance(HOUR)
        day.checkpoint()
        val trusted = day.nowMs()!!

        clock.reboot() // the monotonic clock starts again
        clock.wallMs = trusted - DAY

        assertEquals(trusted, dayClock().nowMs())
    }

    @Test
    fun today_isTheDateInTheGivenZone() {
        clock.wallMs = ZonedDateTime.of(2026, 3, 1, 23, 30, 0, 0, UTC).toInstant().toEpochMilli()

        assertEquals(LocalDate.of(2026, 3, 1), dayClock().today(UTC))
        assertEquals(LocalDate.of(2026, 3, 2), dayClock().today(ZoneId.of("Asia/Kolkata")))
    }

    @Test
    fun msUntilNextDay_isToMidnight_evenOnADaylightSavingDay() {
        val london = ZoneId.of("Europe/London")
        // 31 Mar 2024: the clocks went forward at 01:00, so that day was 23 hours long.
        clock.wallMs = ZonedDateTime.of(2024, 3, 31, 0, 0, 0, 0, london).toInstant().toEpochMilli()

        assertEquals(23 * HOUR, dayClock().msUntilNextDay(london))
    }

    private companion object {
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
