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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts the time a locked app is used under the Guardian's every-day rule,
 * and says how much is left today ([access]).
 *
 * Krypt can't read the screen, so it counts from what the accessibility
 * service reports: the app's time runs while it is the app in front
 * ([onForeground]), the screen is on and unlocked ([onScreenInteractive]),
 * the app is locked, and no one-time Guardian unlock is running (that time
 * is extra). Time is measured on [Clock.elapsedMs], so changing the clock
 * can't add any; the calendar day comes from [TrustedDayClock].
 *
 * Usage is saved every [SAVE_EVERY_MS] and whenever counting stops, so a
 * crash loses at most that much. When today's time runs out - or a rule
 * ends, or the day can't be trusted any more - while the app is in front,
 * the service is told ([attach]) so it can block the app.
 *
 * Not counted: picture-in-picture and background audio (another app is in
 * front then), and in split screen only the app opened last.
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

    /** (package, epoch day) -> milliseconds used; saved ones plus counting not yet saved. */
    private val used = HashMap<Pair<String, Long>, Long>()
    private val unsaved = HashSet<Pair<String, Long>>()

    /** Apps whose rule was saved or ended while [load] was reading; what it read for them is out of date. */
    private val changedWhileLoading = HashSet<String>()

    private var foreground: String? = null
    private var foregroundLocked = false
    private var interactive = true
    private var session: Session? = null
    private var lastSaveElapsed = 0L

    private var scope: CoroutineScope? = null
    private var onAccessEnded: ((String) -> Unit)? = null
    private var timer: Job? = null
    private var grantsWatch: Job? = null

    private val _loaded = MutableStateFlow(false)

    /** True once the saved rules and usage are in memory. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    init {
        appScope.launch { load() }
    }

    override fun access(pkg: String): DailyAccess = synchronized(lock) { accessLocked(pkg) }

    /** The app in front changed ([pkg] null: none known). [locked]: Krypt locks it. */
    fun onForeground(pkg: String?, locked: Boolean) = act {
        foreground = pkg
        foregroundLocked = locked
    }

    /** The screen is on and unlocked (true), or off or at the keyguard (false). */
    fun onScreenInteractive(isInteractive: Boolean) = act { interactive = isInteractive }

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
     * The service stopped. The app in front is forgotten: a service bound again
     * learns it from the next window change, and counting waits until then.
     */
    fun detach() {
        synchronized(lock) {
            accrueLocked()
            session = null
            foreground = null
            foregroundLocked = false
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
            session = null
            foreground = null
            foregroundLocked = false
            interactive = true
            timer?.cancel()
            timer = null
        }
        _changes.value = _changes.value + 1
    }

    private suspend fun load() {
        val saved = try {
            repo.rules() to repo.usage()
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
                savedUsage.forEach { (key, ms) -> used.merge(key, ms, ::maxOf) }
            }
            changedWhileLoading.clear()
            _loaded.value = true
        }
        reevaluate()
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
        ended?.let { e -> e.scope.launch { e.notify(e.pkg) } }
    }

    private class AccessEnded(val pkg: String, val scope: CoroutineScope, val notify: (String) -> Unit)

    /** Returns who to tell when the app in front has just lost its access. */
    private fun updateLocked(): AccessEnded? {
        val want = foreground?.takeIf { pkg ->
            foregroundLocked && interactive && !sessionStore.isUnlockedNow(pkg) &&
                accessLocked(pkg) is DailyAccess.Available
        }
        var ended: String? = null
        val current = session
        if (current != null && current.pkg != want) {
            session = null
            saveLocked()
            val stillInFront = current.pkg == foreground && foregroundLocked && interactive
            if (stillInFront && !sessionStore.isUnlockedNow(current.pkg)) ended = current.pkg
        }
        if (want != null && session == null) startSessionLocked(want)
        if (session != null && clock.elapsedMs() - lastSaveElapsed >= SAVE_EVERY_MS) saveLocked()
        scheduleLocked()
        val notify = onAccessEnded
        val target = scope
        return if (ended != null && notify != null && target != null) AccessEnded(ended, target, notify) else null
    }

    private fun accessLocked(pkg: String): DailyAccess {
        val rule = rules[pkg] ?: return DailyAccess.None
        val today = dayClock.today(rule.zone) ?: return DailyAccess.Paused(rule.minutesPerDay, rule.lastDay)
        if (!rule.covers(today)) return DailyAccess.None
        val usedToday = (used[pkg to today.toEpochDay()] ?: 0L) + countingMs(pkg, today)
        val left = rule.dailyMs - usedToday
        return if (left > 0) {
            DailyAccess.Available(left, rule.minutesPerDay, rule.lastDay)
        } else {
            DailyAccess.UsedUp(rule.minutesPerDay, rule.lastDay)
        }
    }

    /** Time the running session has counted for [pkg] today but not yet added to [used]. */
    private fun countingMs(pkg: String, today: LocalDate): Long {
        val s = session ?: return 0L
        if (s.pkg != pkg || s.day != today) return 0L
        return (clock.elapsedMs() - s.sinceElapsed).coerceAtLeast(0L)
    }

    private fun startSessionLocked(pkg: String) {
        val rule = rules[pkg] ?: return
        val today = dayClock.today(rule.zone) ?: return
        session = Session(pkg, rule.zone, today, clock.elapsedMs())
    }

    /** Move the running session's time into [used], splitting it at midnight if it crossed one. */
    private fun accrueLocked() {
        val s = session ?: return
        val nowElapsed = clock.elapsedMs()
        val delta = nowElapsed - s.sinceElapsed
        if (delta <= 0L) return
        val today = dayClock.today(s.zone)
        if (today == null || today == s.day) {
            // With the day no longer known (automatic time just went off), the time
            // was still used on the day that was.
            add(s.pkg, s.day, delta)
        } else {
            val trustedNow = dayClock.nowMs() ?: 0L
            val startOfToday = today.atStartOfDay(s.zone).toInstant().toEpochMilli()
            val intoToday = (trustedNow - startOfToday).coerceIn(0L, delta)
            add(s.pkg, s.day, delta - intoToday)
            add(s.pkg, today, intoToday)
        }
        session = s.copy(day = today ?: s.day, sinceElapsed = nowElapsed)
    }

    private fun add(pkg: String, day: LocalDate, ms: Long) {
        if (ms <= 0L) return
        val key = pkg to day.toEpochDay()
        used[key] = (used[key] ?: 0L) + ms
        unsaved += key
    }

    private fun saveLocked() {
        lastSaveElapsed = clock.elapsedMs()
        dayClock.checkpoint()
        if (unsaved.isEmpty()) return
        val batch = unsaved.map { it to used.getValue(it) }
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

    /** Wake up when today's time runs out, at midnight, or when it's time to save. */
    private fun scheduleLocked() {
        timer?.cancel()
        timer = null
        val s = session ?: return
        val target = scope ?: return
        val left = (accessLocked(s.pkg) as? DailyAccess.Available)?.remainingMs ?: return
        val untilMidnight = dayClock.msUntilNextDay(s.zone) ?: return
        val untilSave = SAVE_EVERY_MS - (clock.elapsedMs() - lastSaveElapsed)
        val wait = minOf(left, untilMidnight, untilSave).coerceAtLeast(1L)
        timer = target.launch {
            delay(wait)
            reevaluate()
        }
    }

    private data class Session(
        val pkg: String,
        val zone: ZoneId,
        val day: LocalDate,
        /** Counted up to here, on [Clock.elapsedMs]. */
        val sinceElapsed: Long,
    )

    companion object {
        const val SAVE_EVERY_MS = 30_000L
        private const val TAG = "KryptDaily"
    }
}
