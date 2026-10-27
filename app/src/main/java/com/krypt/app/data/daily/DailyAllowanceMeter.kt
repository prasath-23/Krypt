package com.krypt.app.data.daily

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.krypt.app.common.Clock
import com.krypt.app.common.TrustedDayClock
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts the time a locked app is used under the Guardian's every-day rule,
 * and says how much is left in the rule's current period ([access]): today,
 * or the stretch of the clock it counts in, e.g. 14:00-16:00 for "every 2
 * hours".
 *
 * It counts from what the accessibility service reports: an app's time runs
 * while it is in use ([onInUse]: it has a window on screen, in front, in
 * split screen, in a small floating window or in picture-in-picture), the
 * screen is on and unlocked ([onScreenInteractive]), the app is locked, and
 * no one-time Guardian unlock is running (that time is extra). Several apps
 * on screen at once each count. Time is measured on [Clock.elapsedMs], so
 * changing the clock can't add any; the calendar day and time come from
 * [TrustedDayClock].
 *
 * Time used is kept per minute of the clock ([LocalMinutes]) and a period
 * adds up its minutes, so periods of any length count alike, and a rule that
 * changes period still sees the time already used.
 *
 * Usage is saved every [SAVE_EVERY_MS] and whenever counting stops, so a
 * crash loses at most that much. When the period's time runs out - or a rule
 * ends, or the time can't be trusted any more - while the app is in use,
 * the service is told ([attach]) so it can block the app. When a used-up
 * period ends, [changes] ticks, so a lock screen showing it can close.
 *
 * Not counted: background audio, which has no window on screen.
 */
@Singleton
class DailyAllowanceMeter @Inject constructor(
    private val repo: DailyAllowanceRepository,
    private val sessionStore: LockerSessionStore,
    private val dayClock: TrustedDayClock,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : DailyAllowances {

    private val lock = Any()
    private val rules = HashMap<String, DailyAllowance>()

    /** Package -> ([LocalMinutes] minute -> milliseconds used); saved ones plus counting not yet saved. */
    private val used = HashMap<String, TreeMap<Long, Long>>()
    private val unsaved = HashSet<Pair<String, Long>>()

    /** Apps whose rule was saved or ended while [load] was reading; what it read for them is out of date. */
    private val changedWhileLoading = HashSet<String>()

    /** The apps in use (with a window on screen), and whether Krypt locks each. */
    private var inUse: Map<String, Boolean> = emptyMap()
    private var interactive = true

    /** The apps whose time is being counted now. */
    private val sessions = HashMap<String, Session>()
    private var lastSaveElapsed = 0L

    private var scope: CoroutineScope? = null
    private var onAccessEnded: ((String) -> Unit)? = null
    private var timer: Job? = null
    private var grantsWatch: Job? = null

    /** Ticks [changes] when the first used-up period ends, at [refillAtMs] on the trusted clock. */
    private var refillTimer: Job? = null
    private var refillAtMs: Long? = null

    private val _loaded = MutableStateFlow(false)

    /** True once the saved rules and usage are in memory. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    init {
        appScope.launch { load() }
    }

    override fun access(pkg: String): DailyAccess = synchronized(lock) { accessLocked(pkg) }

    /**
     * How long [pkg] could still be used on its every-day time if it stayed
     * in front with the screen on from [afterMs] from now (when a one-time
     * unlock running now ends, say): 0 when it would have none then, null
     * when it would last longer than [withinMs]. A period that starts in the
     * meantime starts full, and the rule's end ends it.
     */
    fun usableMs(pkg: String, afterMs: Long, withinMs: Long): Long? = synchronized(lock) {
        usableLocked(pkg, afterMs, withinMs)
    }

    /**
     * The apps in use changed: each app with a window on screen, and whether
     * Krypt locks it. Each locked one's time counts.
     */
    fun onInUse(apps: Map<String, Boolean>) = act { inUse = HashMap(apps) }

    /** [pkg] alone is in use, in front ([pkg] null: none known). [locked]: Krypt locks it. */
    fun onForeground(pkg: String?, locked: Boolean) = onInUse(if (pkg == null) emptyMap() else mapOf(pkg to locked))

    /** The screen is on and unlocked (true), or off or at the keyguard (false). */
    fun onScreenInteractive(isInteractive: Boolean) = act {
        // Timers stop while the phone sleeps, so the one for a used-up period's end is set again.
        if (isInteractive) forgetRefillLocked()
        interactive = isInteractive
    }

    /** Something outside may have changed what [access] returns: a grant, the clock, the rules. */
    fun reevaluate() = act { }

    /** A consumed approval saved [rule]; used at once, before Room's copy is read back. */
    fun onRuleSaved(rule: DailyAllowance) = act {
        rules[rule.packageName] = rule
        noteRuleChangeLocked(rule.packageName)
    }

    override suspend fun end(pkg: String) {
        act {
            rules.remove(pkg)
            noteRuleChangeLocked(pkg)
        }
        repo.end(pkg)
    }

    /**
     * Start counting for the accessibility service. [onAccessEnded] is called
     * (on [scope]) when the app in front has just lost its every-day access.
     */
    fun attach(scope: CoroutineScope, onAccessEnded: (String) -> Unit) {
        act {
            this.scope = scope
            this.onAccessEnded = onAccessEnded
            grantsWatch?.cancel()
            // A one-time unlock starting or ending changes whether time counts.
            grantsWatch = scope.launch { sessionStore.grants.drop(1).collect { reevaluate() } }
        }
    }

    /**
     * The service stopped. The apps in use are forgotten: a service bound
     * again tells them anew, and counting waits until then.
     */
    fun detach() {
        synchronized(lock) {
            accrueLocked()
            sessions.clear()
            inUse = emptyMap()
            saveLocked()
            timer?.cancel()
            grantsWatch?.cancel()
            timer = null
            grantsWatch = null
            scope = null
            onAccessEnded = null
        }
    }

    /** Forget everything, then load the saved rules and usage again, as a new process would. */
    @VisibleForTesting
    suspend fun reloadForTest() {
        forgetAll()
        load()
    }

    @VisibleForTesting
    fun forgetAll() {
        synchronized(lock) {
            rules.clear()
            used.clear()
            unsaved.clear()
            changedWhileLoading.clear()
            sessions.clear()
            inUse = emptyMap()
            interactive = true
            timer?.cancel()
            timer = null
            forgetRefillLocked()
        }
        _changes.value = _changes.value + 1
    }

    private suspend fun load() {
        val saved = try {
            repo.rules() to repo.usage(fromMinute = recentUseFrom())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without its rules every daily app simply stays locked.
            Log.e(TAG, "could not load every-day rules", e)
            null
        }
        synchronized(lock) {
            if (saved != null) {
                val (savedRules, savedUsage) = saved
                // A rule saved or ended while this was reading is newer than what it read.
                savedRules.filter { it.packageName !in changedWhileLoading }
                    .forEach { rules.putIfAbsent(it.packageName, it) }
                savedUsage.forEach { (key, ms) -> used.getOrPut(key.first) { TreeMap() }.merge(key.second, ms, ::maxOf) }
            }
            changedWhileLoading.clear()
            _loaded.value = true
        }
        reevaluate()
    }

    /**
     * The first minute whose saved use can still count. A period is never
     * longer than a day, so only the last days matter, and loading only them
     * keeps the start quick however long a rule runs: the service blocks
     * nothing until it is done. With no trusted time yet, everything.
     */
    private fun recentUseFrom(): Long {
        val now = dayClock.nowMs() ?: return Long.MIN_VALUE
        return (Math.floorDiv(now, DAY_MS) - DAYS_OF_USE_LOADED) * LocalMinutes.PER_DAY
    }

    /** While the saved rules are still loading, remember that [pkg]'s rule changed. */
    private fun noteRuleChangeLocked(pkg: String) {
        if (!_loaded.value) changedWhileLoading += pkg
    }

    /** Apply [change], then bring counting in line with the new state. */
    private inline fun act(change: () -> Unit) {
        val ended: AccessEnded?
        synchronized(lock) {
            accrueLocked()
            change()
            ended = updateLocked()
        }
        _changes.value = _changes.value + 1
        ended?.let { e -> e.scope.launch { e.pkgs.forEach(e.notify) } }
    }

    private class AccessEnded(val pkgs: List<String>, val scope: CoroutineScope, val notify: (String) -> Unit)

    /** Returns who to tell when apps in use have just lost their access. */
    private fun updateLocked(): AccessEnded? {
        val want = inUse.filter { (pkg, locked) ->
            locked && interactive && !sessionStore.isUnlockedNow(pkg) && accessLocked(pkg) is DailyAccess.Available
        }.keys
        val stopped = sessions.keys.filter { it !in want }
        val ended = ArrayList<String>()
        for (pkg in stopped) {
            sessions.remove(pkg)
            val stillInUse = inUse[pkg] == true && interactive
            if (stillInUse && !sessionStore.isUnlockedNow(pkg)) ended += pkg
        }
        if (stopped.isNotEmpty()) saveLocked()
        for (pkg in want) if (pkg !in sessions) startSessionLocked(pkg)
        if (sessions.isNotEmpty() && clock.elapsedMs() - lastSaveElapsed >= SAVE_EVERY_MS) saveLocked()
        scheduleLocked()
        scheduleRefillLocked()
        val notify = onAccessEnded
        val target = scope
        return if (ended.isNotEmpty() && notify != null && target != null) AccessEnded(ended, target, notify) else null
    }

    private fun accessLocked(pkg: String): DailyAccess {
        val rule = rules[pkg] ?: return DailyAccess.None
        val now = dayClock.nowMs() ?: return DailyAccess.Paused(rule.minutes, rule.lastDay, rule.periodMinutes)
        if (!rule.covers(LocalMinutes.day(LocalMinutes.of(now, rule.zone)))) return DailyAccess.None
        val left = rule.allowedMs - usedLocked(pkg, rule, atMs = now, nowMs = now)
        return if (left > 0) {
            DailyAccess.Available(left, rule.minutes, rule.lastDay, rule.periodMinutes)
        } else {
            DailyAccess.UsedUp(rule.minutes, rule.lastDay, rule.periodMinutes, nextPeriodAt(rule, now))
        }
    }

    /** [usableMs], period by period from [afterMs] on: each step uses up a period or finds the time's end. */
    private fun usableLocked(pkg: String, afterMs: Long, withinMs: Long): Long? {
        val rule = rules[pkg] ?: return 0L
        val now = dayClock.nowMs() ?: return 0L
        var at = now + afterMs
        var usable = 0L
        while (usable <= withinMs) {
            if (!rule.covers(LocalMinutes.day(LocalMinutes.of(at, rule.zone)))) return usable
            val left = rule.allowedMs - usedLocked(pkg, rule, atMs = at, nowMs = now)
            if (left <= 0) return usable
            val end = rule.periodEndMs(at)
            // Time that runs out just as its period ends doesn't stop the app: the next period starts full.
            if (at + left < end) return (usable + left).takeIf { it <= withinMs }
            usable += end - at
            at = end
        }
        return null
    }

    /** The time used in [rule]'s period that [atMs] falls in: what is saved, and what is being counted at [nowMs]. */
    private fun usedLocked(pkg: String, rule: DailyAllowance, atMs: Long, nowMs: Long): Long {
        val period = rule.periodOf(LocalMinutes.of(atMs, rule.zone))
        val saved = used[pkg]?.subMap(period.first, true, period.last, true)?.values?.sum() ?: 0L
        return saved + countingMs(pkg, sinceMs = rule.periodStartMs(atMs), nowMs = nowMs)
    }

    /** When the next period starts, for a rule counted in periods shorter than a day that still covers it then. */
    private fun nextPeriodAt(rule: DailyAllowance, nowMs: Long): Long? {
        if (rule.periodMinutes == DailyAllowance.DAY_MINUTES) return null
        val next = rule.periodEndMs(nowMs)
        return next.takeIf { rule.covers(Instant.ofEpochMilli(it).atZone(rule.zone).toLocalDate()) }
    }

    /** Time [pkg]'s running session has counted since [sinceMs] but not yet added to [used]. */
    private fun countingMs(pkg: String, sinceMs: Long, nowMs: Long): Long {
        val s = sessions[pkg] ?: return 0L
        val counting = (clock.elapsedMs() - s.sinceElapsed).coerceAtLeast(0L)
        return minOf(counting, (nowMs - sinceMs).coerceAtLeast(0L))
    }

    private fun startSessionLocked(pkg: String) {
        val rule = rules[pkg] ?: return
        val now = dayClock.nowMs() ?: return
        sessions[pkg] = Session(pkg, rule.zone, LocalMinutes.of(now, rule.zone), clock.elapsedMs())
    }

    /** Move the running sessions' time into [used], split at the minutes of the clock it crossed. */
    private fun accrueLocked() {
        if (sessions.isEmpty()) return
        val nowElapsed = clock.elapsedMs()
        val now = dayClock.nowMs()
        for (entry in sessions.entries) {
            val s = entry.value
            val delta = nowElapsed - s.sinceElapsed
            if (delta <= 0L) continue
            if (now == null) {
                // With the time no longer known (automatic time just went off), the time
                // was still used in the minute that was.
                add(s.pkg, s.minute, delta)
            } else {
                var from = now - delta
                while (from < now) {
                    val until = minOf(now, LocalMinutes.nextStartMs(from, s.zone))
                    add(s.pkg, LocalMinutes.of(from, s.zone), until - from)
                    from = until
                }
            }
            entry.setValue(s.copy(minute = now?.let { LocalMinutes.of(it, s.zone) } ?: s.minute, sinceElapsed = nowElapsed))
        }
    }

    private fun add(pkg: String, minute: Long, ms: Long) {
        if (ms <= 0L) return
        used.getOrPut(pkg) { TreeMap() }.merge(minute, ms, Long::plus)
        unsaved += pkg to minute
    }

    private fun saveLocked() {
        lastSaveElapsed = clock.elapsedMs()
        dayClock.checkpoint()
        if (unsaved.isEmpty()) return
        val batch = unsaved.map { it to used.getValue(it.first).getValue(it.second) }
        unsaved.clear()
        appScope.launch {
            for ((key, ms) in batch) {
                try {
                    repo.saveUsage(key.first, key.second, ms)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "could not save daily usage for ${key.first}", e)
                }
            }
        }
    }

    /** Wake up when an app's time runs out, when its period ends, or when it's time to save. */
    private fun scheduleLocked() {
        timer?.cancel()
        timer = null
        if (sessions.isEmpty()) return
        val target = scope ?: return
        val now = dayClock.nowMs() ?: return
        var wait = SAVE_EVERY_MS - (clock.elapsedMs() - lastSaveElapsed)
        for (pkg in sessions.keys) {
            val rule = rules[pkg] ?: continue
            val left = (accessLocked(pkg) as? DailyAccess.Available)?.remainingMs ?: continue
            wait = minOf(wait, left, rule.periodEndMs(now) - now)
        }
        val delayMs = wait.coerceAtLeast(1L)
        timer = target.launch {
            delay(delayMs)
            reevaluate()
        }
    }

    /**
     * Wake when the first used-up period ends, so what shows it - the lock
     * screen, Home - hears through [changes] that the time is back. Not tied
     * to the service, since no app need be in front then.
     */
    private fun scheduleRefillLocked() {
        val now = dayClock.nowMs()
        val next = if (now == null) {
            null
        } else {
            rules.values
                .filter { accessLocked(it.packageName) is DailyAccess.UsedUp }
                .minOfOrNull { it.periodEndMs(now) }
        }
        if (next == refillAtMs) return
        refillTimer?.cancel()
        refillTimer = null
        refillAtMs = next
        if (now == null || next == null) return
        refillTimer = appScope.launch {
            delay((next - now).coerceAtLeast(1L))
            // Woken a little early (the trusted clock was corrected): this schedules it again.
            synchronized(lock) { refillAtMs = null }
            reevaluate()
        }
    }

    /** Drop the refill timer, so the next [scheduleRefillLocked] sets it from the clock again. */
    private fun forgetRefillLocked() {
        refillTimer?.cancel()
        refillTimer = null
        refillAtMs = null
    }

    private data class Session(
        val pkg: String,
        val zone: ZoneId,
        /** The [LocalMinutes] minute that counting had reached. */
        val minute: Long,
        /** Counted up to here, on [Clock.elapsedMs]. */
        val sinceElapsed: Long,
    )

    companion object {
        const val SAVE_EVERY_MS = 30_000L
        private const val TAG = "KryptDaily"
        private const val DAY_MS = 24 * 60 * 60_000L

        /** Days of saved use read at start besides today: slack for time zones and clock corrections. */
        private const val DAYS_OF_USE_LOADED = 2
    }
}
