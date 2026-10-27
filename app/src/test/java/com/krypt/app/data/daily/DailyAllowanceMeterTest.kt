package com.krypt.app.data.daily

import com.krypt.app.common.Clock
import com.krypt.app.common.TrustedDayClock
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.security.FakeSharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DailyAllowanceMeterTest {

    private var autoTime = true
    private val repo = FakeDailyAllowanceRepository()
    private val ended = mutableListOf<String>()

    /**
     * Milliseconds the phone slept. They pass on its clocks but not on the
     * test scheduler, as coroutine timers don't run while it sleeps.
     */
    private var sleptMs = 0L

    private class Env(val meter: DailyAllowanceMeter, val sessionStore: LockerSessionStore, val clock: Clock)

    /** A meter whose wall and monotonic clocks both follow the test scheduler's virtual time, and [sleptMs]. */
    private fun TestScope.env(startWall: Long = MORNING): Env {
        val clock = object : Clock {
            override fun nowMs() = startWall + testScheduler.currentTime + sleptMs
            override fun elapsedMs() = testScheduler.currentTime + sleptMs
            override fun bootCount() = 1
        }
        val sessionStore = LockerSessionStore(FakeSharedPreferences(), clock)
        val dayClock = TrustedDayClock(FakeSharedPreferences(), clock) { autoTime }
        val meter = DailyAllowanceMeter(repo, sessionStore, dayClock, clock, backgroundScope)
        runCurrent() // loads the saved rules and usage
        meter.attach(backgroundScope) { ended += it }
        return Env(meter, sessionStore, clock)
    }

    private fun rule(
        minutes: Int = 60,
        days: Int = 2,
        first: LocalDate = TODAY,
        pkg: String = APP,
        periodMinutes: Int = DailyAllowance.DAY_MINUTES,
        zone: ZoneId = UTC,
    ) = DailyAllowance(pkg, minutes, first, first.plusDays(days - 1L), zone, UUID.randomUUID(), 0L, periodMinutes)

    private fun Env.left(): Long = (meter.access(APP) as DailyAccess.Available).remainingMs

    /** What [repo] has saved for [APP] in the minutes [from] until [until]. */
    private fun savedBetween(from: Long, until: Long): Long =
        repo.usage.filterKeys { (pkg, minute) -> pkg == APP && minute in from until until }.values.sum()

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun countsOnlyWhileTheAppIsInFront() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()

        e.meter.onForeground(APP, locked = true)
        pass(10 * MIN)
        e.meter.onForeground(LAUNCHER, locked = false)
        pass(10 * MIN)

        assertEquals(50 * MIN, e.left())
    }

    @Test
    fun theTimeBeingCounted_isIncludedAtOnce() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()

        e.meter.onForeground(APP, locked = true)
        pass(10_000)

        assertEquals(60 * MIN - 10_000, e.left())
    }

    @Test
    fun theScreenOff_pausesCounting() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()

        e.meter.onForeground(APP, locked = true)
        pass(5 * MIN)
        e.meter.onScreenInteractive(false)
        pass(30 * MIN)
        e.meter.onScreenInteractive(true)
        pass(5 * MIN)

        assertEquals(50 * MIN, e.left())
    }

    @Test
    fun oneTimeUnlockTime_isNotCounted() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()
        e.sessionStore.recordGrant(APP, e.clock.nowMs() + 20 * MIN)

        e.meter.onForeground(APP, locked = true)
        pass(20 * MIN)
        e.meter.reevaluate() // the service does this when the grant runs out
        pass(10 * MIN)

        assertEquals(50 * MIN, e.left())
    }

    @Test
    fun whenTodaysTimeRunsOut_theAppInFrontIsBlocked_once() = runTest {
        repo.rules += rule(minutes = 1)
        val e = env()

        e.meter.onForeground(APP, locked = true)
        pass(59_999)
        assertEquals(emptyList<String>(), ended)
        pass(2)

        assertEquals(listOf(APP), ended)
        assertEquals(DailyAccess.UsedUp(1, TODAY.plusDays(1)), e.meter.access(APP))
        pass(10 * MIN)
        assertEquals("told once", listOf(APP), ended)
    }

    @Test
    fun usageIsSavedEveryThirtySeconds_andWhenCountingStops() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()

        e.meter.onForeground(APP, locked = true)
        pass(30_000)
        assertEquals(30_000L, repo.usage[APP to minute(TODAY, 8)])

        pass(10_000)
        e.meter.onForeground(LAUNCHER, locked = false)
        runCurrent()
        assertEquals(40_000L, repo.usage[APP to minute(TODAY, 8)])
    }

    @Test
    fun aRestart_keepsTodaysUsage() = runTest {
        repo.rules += rule(minutes = 60)
        repo.usage[APP to minute(TODAY, 7)] = 25 * MIN

        assertEquals(35 * MIN, env().left())
    }

    @Test
    fun atStart_onlyTheLastDaysOfUseAreRead() = runTest {
        repo.rules += rule(minutes = 60)
        repo.usage[APP to minute(TODAY.minusDays(10), 9)] = 60 * MIN // long ago: it can't count
        repo.usage[APP to minute(TODAY, 7)] = 25 * MIN

        val e = env()

        assertEquals(minute(TODAY.minusDays(2), 0), repo.readFrom)
        assertEquals(35 * MIN, e.left())
    }

    @Test
    fun aDaysUseSavedBeforePeriods_stillCountsInFullForThatDay() = runTest {
        repo.rules += rule(minutes = 60)
        // MIGRATION_2_3 moves a day's saved use to that day's first minute.
        repo.usage[APP to minute(TODAY, 0)] = 50 * MIN

        assertEquals(10 * MIN, env().left())
    }

    @Test
    fun atMidnight_useIsSplitBetweenTheDays_andTheNewDayStartsFresh() = runTest {
        repo.rules += rule(minutes = 60, days = 2)
        val e = env(startWall = MIDNIGHT - 10 * MIN)

        e.meter.onForeground(APP, locked = true)
        pass(20 * MIN)
        e.meter.onForeground(LAUNCHER, locked = false)
        runCurrent()

        assertEquals(10 * MIN, savedBetween(minute(TODAY, 0), minute(TODAY.plusDays(1), 0)))
        assertEquals(10 * MIN, savedBetween(minute(TODAY.plusDays(1), 0), minute(TODAY.plusDays(2), 0)))
        assertEquals(50 * MIN, e.left())
    }

    @Test
    fun afterTheRulesLastDay_theAppInFrontIsBlocked() = runTest {
        repo.rules += rule(minutes = 60, days = 1)
        val e = env(startWall = MIDNIGHT - 5 * MIN)

        e.meter.onForeground(APP, locked = true)
        pass(6 * MIN)

        assertEquals(DailyAccess.None, e.meter.access(APP))
        assertEquals(listOf(APP), ended)
    }

    @Test
    fun switchingAutomaticTimeOff_pausesDailyTime_andBlocksTheAppInFront() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()

        e.meter.onForeground(APP, locked = true)
        pass(MIN)
        autoTime = false
        e.meter.reevaluate()
        runCurrent()

        assertEquals(DailyAccess.Paused(60, TODAY.plusDays(1)), e.meter.access(APP))
        assertEquals(listOf(APP), ended)
    }

    @Test
    fun switchingAutomaticTimeOff_stillCountsTheTimeUsedBeforeIt() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()

        e.meter.onForeground(APP, locked = true)
        pass(10_000)
        autoTime = false
        e.meter.reevaluate()
        runCurrent()

        assertEquals(10_000L, repo.usage[APP to minute(TODAY, 8)])
    }

    @Test
    fun afterTheServiceIsBoundAgain_countingWaitsForTheNextWindowChange() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()
        e.meter.onForeground(APP, locked = true)
        pass(MIN)

        e.meter.detach()
        e.meter.attach(backgroundScope) { ended += it }
        pass(5 * MIN)
        assertEquals("only the minute before", 59 * MIN, e.left())

        e.meter.onForeground(APP, locked = true)
        pass(MIN)
        assertEquals(58 * MIN, e.left())
    }

    @Test
    fun daysAreCountedInTheRulesZone_soAZoneChangeMakesNoExtraDay() = runTest {
        repo.rules += rule(minutes = 60)
        repo.usage[APP to minute(TODAY, 10)] = 60 * MIN
        // 20:00 UTC is already tomorrow in Kolkata, but the rule counts days in UTC.
        val e = env(startWall = MIDNIGHT - 4 * 60 * MIN)

        assertEquals(DailyAccess.UsedUp(60, TODAY.plusDays(1)), e.meter.access(APP))
    }

    @Test
    fun aRuleReplacedMidDay_keepsTodaysUse() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()
        e.meter.onForeground(APP, locked = true)
        pass(20 * MIN)

        e.meter.onRuleSaved(rule(minutes = 30))

        assertEquals(10 * MIN, e.left())
    }

    @Test
    fun endingARule_blocksTheAppInFront() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()
        e.meter.onForeground(APP, locked = true)
        pass(MIN)

        e.meter.end(APP)
        runCurrent()

        assertEquals(DailyAccess.None, e.meter.access(APP))
        assertEquals(listOf(APP), ended)
        assertTrue(APP in repo.ended)
    }

    @Test
    fun appsThatAreNotLocked_areNotCounted() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()

        e.meter.onForeground(APP, locked = false)
        pass(10 * MIN)

        assertEquals(60 * MIN, e.left())
    }

    @Test
    fun aRuleEndedWhileTheSavedRulesLoad_staysEnded() = runTest {
        repo.rules += rule(minutes = 60)
        repo.loadGate = CompletableDeferred()
        val e = env() // still loading

        e.meter.end(APP)
        repo.loadGate!!.complete(Unit)
        runCurrent()

        assertEquals(DailyAccess.None, e.meter.access(APP))
    }

    @Test
    fun aRuleSavedWhileTheSavedRulesLoad_winsOverTheOldOne() = runTest {
        repo.rules += rule(minutes = 60)
        repo.loadGate = CompletableDeferred()
        val e = env() // still loading

        e.meter.onRuleSaved(rule(minutes = 30))
        repo.loadGate!!.complete(Unit)
        runCurrent()

        assertEquals(30 * MIN, e.left())
    }

    @Test
    fun aRuleThatStartsTomorrow_givesNothingToday() = runTest {
        repo.rules += rule(minutes = 60, first = TODAY.plusDays(1))

        assertEquals(DailyAccess.None, env().meter.access(APP))
    }

    // ---- Periods shorter than a day (Amendment 6) ----

    @Test
    fun anHourlyRule_runsOut_andIsBlocked_untilTheNextHour() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env() // 08:00

        e.meter.onForeground(APP, locked = true)
        pass(15 * MIN)

        assertEquals(listOf(APP), ended)
        assertEquals(
            DailyAccess.UsedUp(15, TODAY.plusDays(1), periodMinutes = 60, backAtMs = MORNING + 60 * MIN),
            e.meter.access(APP),
        )
    }

    @Test
    fun aPeriodsTime_canBeUsedAnyTimeInsideIt() = runTest {
        repo.rules += rule(minutes = 30, periodMinutes = 120)
        val e = env() // 08:00, in the 08:00-10:00 period

        e.meter.onForeground(APP, locked = true)
        pass(10 * MIN)
        e.meter.onForeground(LAUNCHER, locked = false)
        pass(80 * MIN) // 09:30
        e.meter.onForeground(APP, locked = true)
        pass(15 * MIN)

        assertEquals(5 * MIN, e.left())
        assertEquals(emptyList<String>(), ended)
    }

    @Test
    fun useAcrossAPeriodsEnd_isSplit_andTheNextPeriodStartsFull() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env(startWall = MORNING + 50 * MIN) // 08:50

        e.meter.onForeground(APP, locked = true)
        pass(20 * MIN) // 09:10: 10 minutes in each hour
        e.meter.onForeground(LAUNCHER, locked = false)
        runCurrent()

        assertEquals("never blocked: 5 minutes were still left at 09:00", emptyList<String>(), ended)
        assertEquals(10 * MIN, savedBetween(minute(TODAY, 8), minute(TODAY, 9)))
        assertEquals(10 * MIN, savedBetween(minute(TODAY, 9), minute(TODAY, 10)))
        assertEquals(5 * MIN, e.left())
    }

    @Test
    fun whenAUsedUpPeriodEnds_changesTicks_soTheLockScreenCanClose() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env() // 08:00
        e.meter.onForeground(APP, locked = true)
        pass(15 * MIN)
        e.meter.onForeground(KRYPT, locked = false) // the lock screen is in front now
        runCurrent()
        val before = e.meter.changes.value

        pass(44 * MIN) // 08:59
        assertTrue(e.meter.access(APP) is DailyAccess.UsedUp)
        pass(MIN) // 09:00

        assertTrue("changes ticked when the hour turned", e.meter.changes.value > before)
        assertEquals(DailyAccess.Available(15 * MIN, 15, TODAY.plusDays(1), periodMinutes = 60), e.meter.access(APP))
    }

    @Test
    fun theRulesLastPeriod_hasNoTimeToComeBackAt() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 120, days = 1)
        val e = env(startWall = MIDNIGHT - 90 * MIN) // 22:30 on the rule's only day

        e.meter.onForeground(APP, locked = true)
        pass(15 * MIN)

        assertEquals(DailyAccess.UsedUp(15, TODAY, periodMinutes = 120, backAtMs = null), e.meter.access(APP))
    }

    @Test
    fun periodsFollowTheRulesZone() = runTest {
        // 08:00 UTC is 13:30 in Kolkata, so its hour ends at 08:30 UTC.
        repo.rules += rule(minutes = 15, periodMinutes = 60, zone = KOLKATA)
        val e = env()

        e.meter.onForeground(APP, locked = true)
        pass(15 * MIN)

        assertEquals(
            DailyAccess.UsedUp(15, TODAY.plusDays(1), periodMinutes = 60, backAtMs = MORNING + 30 * MIN),
            e.meter.access(APP),
        )
        e.meter.onForeground(KRYPT, locked = false)
        pass(15 * MIN) // 08:30 UTC, 14:00 in Kolkata
        assertEquals(DailyAccess.Available(15 * MIN, 15, TODAY.plusDays(1), periodMinutes = 60), e.meter.access(APP))
    }

    @Test
    fun aDailyRuleReplacedByAnHourlyOne_keepsThisHoursUse() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env() // 08:00
        e.meter.onForeground(APP, locked = true)
        pass(20 * MIN)

        e.meter.onRuleSaved(rule(minutes = 15, periodMinutes = 60))
        runCurrent()

        assertTrue(e.meter.access(APP) is DailyAccess.UsedUp)
        assertEquals(listOf(APP), ended)
    }

    @Test(timeout = 10_000)
    fun theNightLordHowesClocksGoBackHalfAnHour_useIsCountedOnce() = runTest {
        // 02:00 (+11:00) becomes 01:30 (+10:30), so 01:30-02:00 happens twice.
        val back = LORD_HOWE.rules.nextTransition(Instant.parse("2026-03-01T00:00:00Z")).instant.toEpochMilli()
        repo.rules += rule(minutes = 60, first = LocalDate.of(2026, 4, 4), days = 3, zone = LORD_HOWE)
        val e = env(startWall = back - 20 * MIN)

        e.meter.onForeground(APP, locked = true)
        pass(40 * MIN)
        e.meter.onForeground(LAUNCHER, locked = false)
        runCurrent()

        assertEquals(40 * MIN, repo.usage.values.sum())
        assertEquals(20 * MIN, e.left())
    }

    @Test
    fun aHalfHourRule_comesBackEveryHalfHour() = runTest {
        repo.rules += rule(minutes = 10, periodMinutes = 30)
        val e = env() // 08:00

        e.meter.onForeground(APP, locked = true)
        pass(10 * MIN)
        assertEquals(
            DailyAccess.UsedUp(10, TODAY.plusDays(1), periodMinutes = 30, backAtMs = MORNING + 30 * MIN),
            e.meter.access(APP),
        )
        e.meter.onForeground(KRYPT, locked = false)
        pass(20 * MIN) // 08:30

        assertEquals(DailyAccess.Available(10 * MIN, 10, TODAY.plusDays(1), periodMinutes = 30), e.meter.access(APP))
    }

    @Test
    fun aPeriodOfAnyLength_startsFromMidnight() = runTest {
        // Every 45 minutes: ..., 07:30-08:15, 08:15-09:00, ...
        repo.rules += rule(minutes = 10, periodMinutes = 45)
        val e = env() // 08:00

        e.meter.onForeground(APP, locked = true)
        pass(10 * MIN)

        assertEquals(
            DailyAccess.UsedUp(10, TODAY.plusDays(1), periodMinutes = 45, backAtMs = MORNING + 15 * MIN),
            e.meter.access(APP),
        )
    }

    @Test
    fun whenThePeriodDoesntDivideADay_theDaysLastOneEndsAtMidnight() = runTest {
        // Every 5 hours: 20:00-24:00 is the day's last, shorter period.
        repo.rules += rule(minutes = 30, periodMinutes = 300)
        val e = env(startWall = MIDNIGHT - 3 * 60 * MIN) // 21:00

        e.meter.onForeground(APP, locked = true)
        pass(30 * MIN)

        assertEquals(
            DailyAccess.UsedUp(30, TODAY.plusDays(1), periodMinutes = 300, backAtMs = MIDNIGHT),
            e.meter.access(APP),
        )
    }

    @Test
    fun automaticTimeOff_pausesAnHourlyRule() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env()
        autoTime = false

        assertEquals(DailyAccess.Paused(15, TODAY.plusDays(1), periodMinutes = 60), e.meter.access(APP))
    }

    @Test
    fun aRefillTimerThatSlept_isSetAgainWhenTheScreenComesOn() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env() // 08:00
        e.meter.onForeground(APP, locked = true)
        pass(15 * MIN) // used up until 09:00
        e.meter.onForeground(KRYPT, locked = false) // the lock screen
        e.meter.onScreenInteractive(false)
        runCurrent()

        sleptMs = 40 * MIN // 08:55 when the screen comes on; the timers lost those 40 minutes
        e.meter.onScreenInteractive(true)
        runCurrent()
        val before = e.meter.changes.value
        pass(5 * MIN - 1)
        assertEquals(before, e.meter.changes.value)
        pass(1)

        assertTrue("the lock screen hears at 09:00, not 40 minutes later", e.meter.changes.value > before)
    }

    // ---- Several apps on screen at once (Amendment 8) ----

    @Test
    fun anAppInASmallWindowOverAnotherApp_counts() = runTest {
        repo.rules += rule(minutes = 60)
        val e = env()

        e.meter.onInUse(mapOf(LAUNCHER to false, APP to true))
        pass(10 * MIN)

        assertEquals(50 * MIN, e.left())
    }

    @Test
    fun twoLockedAppsOnScreen_bothCount() = runTest {
        repo.rules += rule(minutes = 60)
        repo.rules += rule(minutes = 60, pkg = OTHER)
        val e = env()

        e.meter.onInUse(mapOf(APP to true, OTHER to true)) // OTHER in a small window over APP, say
        pass(10 * MIN)

        assertEquals(50 * MIN, e.left())
        assertEquals(50 * MIN, (e.meter.access(OTHER) as DailyAccess.Available).remainingMs)
    }

    @Test
    fun whenOneOfTwoAppsRunsOut_onlyItIsBlocked_andTheOtherGoesOnCounting() = runTest {
        repo.rules += rule(minutes = 60)
        repo.rules += rule(minutes = 1, pkg = OTHER)
        val e = env()

        e.meter.onInUse(mapOf(APP to true, OTHER to true))
        pass(MIN)
        assertEquals(listOf(OTHER), ended)
        pass(MIN)

        assertEquals(58 * MIN, e.left())
    }

    @Test
    fun closingTheSmallWindow_stopsOnlyItsCount() = runTest {
        repo.rules += rule(minutes = 60)
        repo.rules += rule(minutes = 60, pkg = OTHER)
        val e = env()
        e.meter.onInUse(mapOf(APP to true, OTHER to true))
        pass(5 * MIN)

        e.meter.onInUse(mapOf(APP to true))
        pass(5 * MIN)

        assertEquals(50 * MIN, e.left())
        assertEquals(55 * MIN, (e.meter.access(OTHER) as DailyAccess.Available).remainingMs)
        assertEquals("closed by the user, not run out", emptyList<String>(), ended)
    }

    // ---- How long the app in front can go on (Amendment 7) ----

    @Test
    fun usableMs_isTheTimeLeft_whenItRunsOutInThisPeriod() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env() // 08:00
        e.meter.onForeground(APP, locked = true)
        pass(5 * MIN)

        assertEquals(10 * MIN, e.meter.usableMs(APP, afterMs = 0, withinMs = 60 * MIN))
    }

    @Test
    fun usableMs_includesTheTimeBeingCounted() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env()
        e.meter.onForeground(APP, locked = true)
        pass(10_000)

        assertEquals(15 * MIN - 10_000, e.meter.usableMs(APP, afterMs = 0, withinMs = 60 * MIN))
    }

    @Test
    fun usableMs_isNull_whenTheTimeLastsLongerThanAskedAbout() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env()

        assertEquals(null, e.meter.usableMs(APP, afterMs = 0, withinMs = 14 * MIN))
        assertEquals(15 * MIN, e.meter.usableMs(APP, afterMs = 0, withinMs = 15 * MIN))
    }

    @Test
    fun usableMs_runsOnIntoTheNextPeriod_whenThisOneEndsFirst() = runTest {
        // 1 minute every 5: at 08:04:30 a full minute is left but only 30 s of the period,
        // and 08:05-08:10 starts with another minute, so the app goes on until 08:06.
        repo.rules += rule(minutes = 1, periodMinutes = 5)
        val e = env(startWall = MORNING + 4 * MIN + 30_000)

        assertEquals(90_000L, e.meter.usableMs(APP, afterMs = 0, withinMs = 3 * MIN))
    }

    @Test
    fun timeThatRunsOutJustAsItsPeriodEnds_goesOnInTheNextPeriod() = runTest {
        repo.rules += rule(minutes = 1, periodMinutes = 5)
        val e = env(startWall = MORNING + 4 * MIN) // 08:04: a minute of time and a minute of the period

        assertEquals(2 * MIN, e.meter.usableMs(APP, afterMs = 0, withinMs = 10 * MIN))
    }

    @Test
    fun timeAllowedForAWholePeriod_neverRunsOut() = runTest {
        repo.rules += rule(minutes = 60, periodMinutes = 60)
        val e = env()

        assertEquals(null, e.meter.usableMs(APP, afterMs = 0, withinMs = 3 * 60 * MIN))
    }

    @Test
    fun usableMs_endsWhereTheRuleEnds() = runTest {
        repo.rules += rule(minutes = 120, days = 1) // two hours today, nothing tomorrow
        val e = env(startWall = MIDNIGHT - MIN) // 23:59

        assertEquals(MIN, e.meter.usableMs(APP, afterMs = 0, withinMs = 3 * MIN))
    }

    @Test
    fun usableMs_isNothing_withoutARule_whenUsedUp_orWhenPaused() = runTest {
        repo.rules += rule(minutes = 1)
        val e = env()
        assertEquals("no rule", 0L, e.meter.usableMs(LAUNCHER, afterMs = 0, withinMs = MIN))

        e.meter.onForeground(APP, locked = true)
        pass(MIN)
        assertEquals("used up", 0L, e.meter.usableMs(APP, afterMs = 0, withinMs = MIN))

        autoTime = false
        assertEquals("paused", 0L, e.meter.usableMs(APP, afterMs = 0, withinMs = MIN))
    }

    @Test
    fun usableMs_afterAnUnlock_countsFromWhereItEnds() = runTest {
        repo.rules += rule(minutes = 15, periodMinutes = 60)
        val e = env() // 08:00
        e.meter.onForeground(APP, locked = true)
        pass(5 * MIN)
        e.meter.onForeground(LAUNCHER, locked = false)
        runCurrent() // 08:05, 10 minutes of this hour left

        assertEquals("from 08:35", 10 * MIN, e.meter.usableMs(APP, afterMs = 30 * MIN, withinMs = 60 * MIN))
        assertEquals("08:50-09:00, then 09:00-09:15", 25 * MIN, e.meter.usableMs(APP, afterMs = 45 * MIN, withinMs = 60 * MIN))
        assertEquals("a later hour starts full", 15 * MIN, e.meter.usableMs(APP, afterMs = 2 * 60 * MIN, withinMs = 60 * MIN))
    }

    private companion object {
        const val APP = "com.example.app"
        const val OTHER = "com.example.other"
        const val LAUNCHER = "com.example.launcher"
        const val KRYPT = "com.krypt.app"
        const val MIN = 60_000L
        val UTC: ZoneId = ZoneId.of("UTC")
        val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")
        val LORD_HOWE: ZoneId = ZoneId.of("Australia/Lord_Howe")

        /** The [LocalMinutes] minute that [hourOfDay]:[minuteOfHour] on [day] is. */
        fun minute(day: LocalDate, hourOfDay: Int, minuteOfHour: Int = 0): Long =
            day.toEpochDay() * 1440 + hourOfDay * 60 + minuteOfHour
        val TODAY: LocalDate = LocalDate.of(2026, 3, 1)
        val MORNING = ZonedDateTime.of(2026, 3, 1, 8, 0, 0, 0, UTC).toInstant().toEpochMilli()
        val MIDNIGHT = ZonedDateTime.of(2026, 3, 2, 0, 0, 0, 0, UTC).toInstant().toEpochMilli()
    }
}

/**
 * In-memory [DailyAllowanceRepository]. [usage] is both what loads at start and what saves write to.
 * With [loadGate] set, [rules] reads the rules at once but returns them only when the gate opens.
 */
class FakeDailyAllowanceRepository : DailyAllowanceRepository {
    val rules = mutableListOf<DailyAllowance>()
    val usage = HashMap<Pair<String, Long>, Long>()
    val ended = mutableListOf<String>()
    var loadGate: CompletableDeferred<Unit>? = null

    override suspend fun rules(): List<DailyAllowance> {
        val read = rules.toList()
        loadGate?.await()
        return read
    }

    /** Where the last [usage] read started. */
    var readFrom: Long? = null

    override suspend fun usage(fromMinute: Long): Map<Pair<String, Long>, Long> {
        readFrom = fromMinute
        return HashMap(usage.filterKeys { it.second >= fromMinute })
    }

    override suspend fun saveUsage(pkg: String, minute: Long, usedMs: Long) {
        val key = pkg to minute
        if (usedMs > (usage[key] ?: 0L)) usage[key] = usedMs
    }

    override suspend fun end(pkg: String) {
        ended += pkg
        rules.removeAll { it.packageName == pkg }
    }

    override suspend fun prune(rulesEndedBefore: Long, usageBeforeMinute: Long) = Unit
}
