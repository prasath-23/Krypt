package com.krypt.app.service

import com.krypt.app.common.Clock
import com.krypt.app.common.TrustedDayClock
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.daily.DailyAllowance
import com.krypt.app.data.daily.DailyAllowanceMeter
import com.krypt.app.data.daily.FakeDailyAllowanceRepository
import com.krypt.app.security.FakeSharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TimeLeftWatcherTest {

    /** What the countdown showed, each change in order: the time, or null when it was taken down. */
    private val shown = mutableListOf<String?>()

    /** When the watcher said the app's time was up, on the phone's clock (ms since the test began). */
    private val timeUp = mutableListOf<Long>()
    private val grantEnded = mutableListOf<String>()

    /**
     * Milliseconds the phone slept. They pass on the phone's clocks but not
     * on the test scheduler, as coroutine timers don't run while it sleeps.
     */
    private var sleptMs = 0L

    /** [APP]'s every-day time in the tests that make it up: (afterMs, withinMs) -> usable. None by default. */
    private var daily: (Long, Long) -> Long? = { _, _ -> 0L }
    private var usableAsks = 0

    private val repo = FakeDailyAllowanceRepository()

    private class Env(val watcher: TimeLeftWatcher, val store: LockerSessionStore, val clock: Clock)

    private fun TestScope.clock(startWall: Long) = object : Clock {
        override fun nowMs() = startWall + elapsedMs()
        override fun elapsedMs() = testScheduler.currentTime + sleptMs
        override fun bootCount() = 1
    }

    private fun TestScope.watcher(
        store: LockerSessionStore,
        clock: Clock,
        usable: (String, Long, Long) -> Long?,
        onGrantEnded: (String) -> Unit = {},
    ) = TimeLeftWatcher(
        scope = backgroundScope,
        grantMs = store::remainingMs,
        usableMs = { pkg, after, within ->
            usableAsks++
            usable(pkg, after, within)
        },
        display = object : TimeLeftDisplay {
            override fun show(pkg: String, msLeft: Long) {
                val time = timeLeftText(msLeft)
                if (shown.lastOrNull() != time) shown += time
            }

            override fun hide() {
                if (shown.isNotEmpty() && shown.last() != null) shown += null
            }
        },
        onGrantEnded = {
            grantEnded += it
            onGrantEnded(it)
        },
        onTimeUp = { timeUp += clock.elapsedMs() },
    )

    /** A watcher with [APP]'s every-day time from [daily]. */
    private fun TestScope.env(): Env {
        val clock = clock(MORNING)
        val store = LockerSessionStore(FakeSharedPreferences(), clock)
        return Env(watcher(store, clock, { _, after, within -> daily(after, within) }), store, clock)
    }

    /**
     * [APP] in front with its every-day time counted by a real meter, after
     * an unlock of [grantMs] if any. As the service does, the watcher looks
     * again whenever the meter changes, and an unlock ending is told to it.
     */
    private fun TestScope.meterEnv(startWall: Long, grantMs: Long = 0L) {
        val clock = clock(startWall)
        val store = LockerSessionStore(FakeSharedPreferences(), clock)
        if (grantMs > 0) store.recordGrant(APP, clock.nowMs() + grantMs)
        val dayClock = TrustedDayClock(FakeSharedPreferences(), clock) { true }
        val meter = DailyAllowanceMeter(repo, store, dayClock, clock, backgroundScope)
        runCurrent() // loads the rules
        meter.attach(backgroundScope) { }
        val watcher = watcher(store, clock, meter::usableMs, onGrantEnded = { meter.reevaluate() })
        meter.onForeground(APP, locked = true)
        backgroundScope.launch { meter.changes.collect { watcher.watch(APP) } }
        runCurrent()
    }

    private fun rule(minutes: Int, days: Int = 2, periodMinutes: Int = DailyAllowance.DAY_MINUTES) =
        DailyAllowance(APP, minutes, TODAY, TODAY.plusDays(days - 1L), UTC, UUID.randomUUID(), 0L, periodMinutes)

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** What the countdown shows from [fromSeconds] down to its last second, and then taken down. */
    private fun countdownFrom(fromSeconds: Int): List<String?> = (fromSeconds downTo 1).map { timeLeftText(it * 1_000L) } + null

    // ---- One-time unlocks ----

    @Test
    fun anUnlockThatEnds_whileTheAppIsInFront_endsItsTimeOnTheDot() = runTest {
        val e = env()
        e.store.recordGrant(APP, e.clock.nowMs() + 10 * MIN)
        e.watcher.watch(APP)

        pass(10 * MIN - 1)
        assertEquals(emptyList<Long>(), timeUp)
        pass(1)

        assertEquals(listOf(10 * MIN), timeUp)
    }

    @Test
    fun theCountdown_showsForTheLastTwoMinutes_secondBySecond() = runTest {
        val e = env()
        e.store.recordGrant(APP, e.clock.nowMs() + 3 * MIN)
        e.watcher.watch(APP)

        pass(MIN - 1)
        assertEquals(emptyList<String?>(), shown)
        pass(1)
        assertEquals(listOf<String?>("2:00"), shown)
        pass(1_000)
        assertEquals(listOf<String?>("2:00", "1:59"), shown)
        pass(2 * MIN - 1_000)

        assertEquals(countdownFrom(120), shown)
        assertEquals(listOf(3 * MIN), timeUp)
    }

    @Test
    fun afterThePhoneSlept_theTimeIsWorkedOutAgain_soItStillEndsOnTime() = runTest {
        // The reported bug: unlocked for 15 minutes with the screen off for 10 of them. A timer
        // counts only the time the phone is awake, so the old one ended the unlock 10 minutes late.
        val e = env()
        e.store.recordGrant(APP, e.clock.nowMs() + 15 * MIN)
        e.watcher.watch(APP)
        pass(2 * MIN)

        e.watcher.watch(null) // the screen goes off
        sleptMs = 10 * MIN
        e.watcher.watch(APP) // on and unlocked again, the app still in front
        pass(3 * MIN - 1)
        assertEquals(emptyList<Long>(), timeUp)
        pass(1)

        assertEquals(listOf(15 * MIN), timeUp)
    }

    @Test
    fun evenWhenNothingSaysThePhoneWokeUp_theTimeIsLookedAtWithinAMinute() = runTest {
        val e = env()
        e.store.recordGrant(APP, e.clock.nowMs() + 15 * MIN)
        e.watcher.watch(APP)
        pass(2 * MIN)

        sleptMs = 30 * MIN // slept past the unlock's end
        pass(TimeLeftWatcher.MAX_WAIT_MS)

        assertEquals("a minute after waking", listOf(33 * MIN), timeUp)
    }

    @Test
    fun leavingTheApp_takesTheCountdownDown_andStopsWatchingIt() = runTest {
        val e = env()
        e.store.recordGrant(APP, e.clock.nowMs() + MIN)
        e.watcher.watch(APP)
        runCurrent()
        assertEquals(listOf<String?>("1:00"), shown)

        e.watcher.watch(null)
        pass(5 * MIN)

        assertEquals(listOf("1:00", null), shown)
        assertEquals(emptyList<Long>(), timeUp)
    }

    @Test
    fun moreTimeDuringTheCountdown_takesItDown() = runTest {
        val e = env()
        e.store.recordGrant(APP, e.clock.nowMs() + MIN)
        e.watcher.watch(APP)
        pass(30_000)

        e.store.recordGrant(APP, e.clock.nowMs() + 30 * MIN)
        e.watcher.watch(APP) // the service looks again at every new unlock
        pass(5 * MIN)

        assertEquals(null, shown.last())
        assertEquals(emptyList<Long>(), timeUp)
    }

    @Test
    fun whileTheAppStaysInFrontAfterItsTimeEnded_itIsToldToGoEverySecond() = runTest {
        val e = env()
        e.store.recordGrant(APP, e.clock.nowMs() + 10_000)
        e.watcher.watch(APP)

        pass(13_000)

        assertEquals(listOf(10_000L, 11_000L, 12_000L, 13_000L), timeUp)
    }

    @Test
    fun anAppWithNoTimeAtAll_isToldToGoAtOnce() = runTest {
        val e = env()
        e.watcher.watch(APP)
        runCurrent()

        assertEquals(listOf(0L), timeUp)
        assertEquals(emptyList<String?>(), shown)
    }

    @Test
    fun timeThatDoesntEndSoon_showsNothing_andIsLookedAtOnceAMinute() = runTest {
        daily = { _, _ -> null } // more than the watcher asks about
        val e = env()
        e.watcher.watch(APP)
        runCurrent()
        val asked = usableAsks

        pass(10 * MIN)

        assertEquals(emptyList<String?>(), shown)
        assertEquals(emptyList<Long>(), timeUp)
        assertEquals(asked + 10, usableAsks)
    }

    @Test
    fun anUnlockEndingIntoPlentyOfEveryDayTime_isNoEnd_andIsTold() = runTest {
        daily = { _, _ -> null }
        val e = env()
        e.store.recordGrant(APP, e.clock.nowMs() + 2 * MIN)
        e.watcher.watch(APP)

        pass(3 * MIN)

        assertEquals(listOf(APP), grantEnded)
        assertEquals(emptyList<Long>(), timeUp)
        assertEquals(emptyList<String?>(), shown)
    }

    // ---- Every-day time, counted by the meter ----

    @Test
    fun everyDayTimeRunningOut_countsDown_thenEnds() = runTest {
        repo.rules += rule(minutes = 3)
        meterEnv(MORNING)

        pass(MIN)
        assertEquals(listOf<String?>("2:00"), shown)
        pass(2 * MIN)

        assertEquals(3 * MIN, timeUp.first())
        assertEquals(countdownFrom(120), shown)
    }

    @Test
    fun theCountdownRunsOn_acrossAPeriodsEnd_whenTheNextPeriodStartsFull() = runTest {
        // 1 minute every 5, from 08:04:30: the 30 s left of this period, then the minute of 08:05-08:10.
        repo.rules += rule(minutes = 1, periodMinutes = 5)
        meterEnv(MORNING + 4 * MIN + 30_000)

        assertEquals(listOf<String?>("1:30"), shown)
        pass(90_000)

        assertEquals(90_000L, timeUp.first())
        assertEquals(countdownFrom(90), shown)
    }

    @Test
    fun onTheRulesLastDay_theTimeEndsAtMidnight() = runTest {
        repo.rules += rule(minutes = 120, days = 1)
        meterEnv(MIDNIGHT - MIN) // 23:59, with two hours of today's time left

        assertEquals(listOf<String?>("1:00"), shown)
        pass(MIN)

        assertEquals(MIN, timeUp.first())
    }

    @Test
    fun anUnlockEndingIntoAMinuteOfEveryDayTime_countsDownOverBoth() = runTest {
        repo.rules += rule(minutes = 1)
        meterEnv(MORNING, grantMs = MIN) // unlocked for a minute, then today's minute

        assertEquals(listOf<String?>("2:00"), shown)
        pass(2 * MIN)

        assertEquals(listOf(APP), grantEnded)
        assertEquals(2 * MIN, timeUp.first())
        assertEquals(countdownFrom(120), shown)
    }

    @Test
    fun theTimeShows_inMinutesAndSeconds_roundedUp() {
        assertEquals("2:00", timeLeftText(120_000))
        assertEquals("2:00", timeLeftText(119_001))
        assertEquals("1:59", timeLeftText(119_000))
        assertEquals("1:01", timeLeftText(61_000))
        assertEquals("0:01", timeLeftText(1))
        assertEquals("0:00", timeLeftText(0))
    }

    private companion object {
        const val APP = "com.example.app"
        const val MIN = 60_000L
        val UTC: ZoneId = ZoneId.of("UTC")
        val TODAY: LocalDate = LocalDate.of(2026, 3, 1)
        val MORNING = ZonedDateTime.of(2026, 3, 1, 8, 0, 0, 0, UTC).toInstant().toEpochMilli()
        val MIDNIGHT = ZonedDateTime.of(2026, 3, 2, 0, 0, 0, 0, UTC).toInstant().toEpochMilli()
    }
}
