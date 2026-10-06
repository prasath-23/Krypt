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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DailyAllowanceMeterTest {

    private var autoTime = true
    private val repo = FakeDailyAllowanceRepository()
    private val ended = mutableListOf<String>()

    private class Env(val meter: DailyAllowanceMeter, val sessionStore: LockerSessionStore, val clock: Clock)

    /** A meter whose wall and monotonic clocks both follow the test scheduler's virtual time. */
    private fun TestScope.env(startWall: Long = MORNING): Env {
        val clock = object : Clock {
            override fun nowMs() = startWall + testScheduler.currentTime
            override fun elapsedMs() = testScheduler.currentTime
            override fun bootCount() = 1
        }
        val sessionStore = LockerSessionStore(FakeSharedPreferences(), clock)
        val dayClock = TrustedDayClock(FakeSharedPreferences(), clock) { autoTime }
        val meter = DailyAllowanceMeter(repo, sessionStore, dayClock, clock, backgroundScope)
        runCurrent() // loads the saved rules and usage
        meter.attach(backgroundScope) { ended += it }
        return Env(meter, sessionStore, clock)
    }

    private fun rule(minutes: Int = 60, days: Int = 2, first: LocalDate = TODAY, pkg: String = APP) =
        DailyAllowance(pkg, minutes, first, first.plusDays(days - 1L), UTC, UUID.randomUUID(), 0L)

    private fun Env.left(): Long = (meter.access(APP) as DailyAccess.Available).remainingMs

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
        assertEquals(30_000L, repo.usage[APP to TODAY.toEpochDay()])

        pass(10_000)
        e.meter.onForeground(LAUNCHER, locked = false)
        runCurrent()
        assertEquals(40_000L, repo.usage[APP to TODAY.toEpochDay()])
    }

    @Test
    fun aRestart_keepsTodaysUsage() = runTest {
        repo.rules += rule(minutes = 60)
        repo.usage[APP to TODAY.toEpochDay()] = 25 * MIN

        assertEquals(35 * MIN, env().left())
    }

    @Test
    fun atMidnight_useIsSplitBetweenTheDays_andTheNewDayStartsFresh() = runTest {
        repo.rules += rule(minutes = 60, days = 2)
        val e = env(startWall = MIDNIGHT - 10 * MIN)

        e.meter.onForeground(APP, locked = true)
        pass(20 * MIN)
        e.meter.onForeground(LAUNCHER, locked = false)
        runCurrent()

        assertEquals(10 * MIN, repo.usage[APP to TODAY.toEpochDay()])
        assertEquals(10 * MIN, repo.usage[APP to TODAY.plusDays(1).toEpochDay()])
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

        assertEquals(10_000L, repo.usage[APP to TODAY.toEpochDay()])
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
        repo.usage[APP to TODAY.toEpochDay()] = 60 * MIN
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

    private companion object {
        const val APP = "com.example.app"
        const val LAUNCHER = "com.example.launcher"
        const val MIN = 60_000L
        val UTC: ZoneId = ZoneId.of("UTC")
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

    override suspend fun usage(): Map<Pair<String, Long>, Long> = HashMap(usage)

    override suspend fun saveUsage(pkg: String, epochDay: Long, usedMs: Long) {
        val key = pkg to epochDay
        if (usedMs > (usage[key] ?: 0L)) usage[key] = usedMs
    }

    override suspend fun end(pkg: String) {
        ended += pkg
        rules.removeAll { it.packageName == pkg }
    }

    override suspend fun prune(rulesEndedBefore: Long, usageBefore: Long) = Unit
}
