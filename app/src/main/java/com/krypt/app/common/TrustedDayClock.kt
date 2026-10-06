package com.krypt.app.common

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the device's "Set time automatically" is on. A seam so tests can switch it. */
fun interface AutoTimeSetting {
    fun isOn(): Boolean
}

class SystemAutoTimeSetting @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoTimeSetting {
    override fun isOn(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) == 1
}

/**
 * The time and calendar day that every-day allowances count against,
 * hardened against the child changing the date.
 *
 * - Nothing is trusted while automatic date & time is off: [nowMs] and
 *   [today] return null, and daily time is paused.
 * - Within a boot, time runs on [Clock.elapsedMs] from an anchor, so a date
 *   changed by hand (which needs automatic time off) never moves the day,
 *   even after automatic time is back on. A change while automatic time is
 *   on is a network correction, and [onTimeSet] follows it.
 * - After a reboot the anchor comes from the wall clock again, but never
 *   earlier than the latest time already trusted ([checkpoint]), so the day
 *   can't go backwards and a used-up day can't come round again.
 *
 * Residual risk: offline, a child who moves the date forward, turns
 * automatic time back on and reboots can use later days' time early. Each
 * day's allowance still counts once, and the rule still ends on its last day.
 */
@Singleton
class TrustedDayClock internal constructor(
    private val prefs: SharedPreferences,
    private val clock: Clock,
    private val autoTime: AutoTimeSetting,
) {

    @Inject constructor(
        @ApplicationContext context: Context,
        clock: Clock,
        autoTime: AutoTimeSetting,
    ) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), clock, autoTime)

    private var anchor: Anchor? = null

    /** When automatic time was last switched on, on [Clock.elapsedMs]; null if not seen. */
    private var autoTimeOnSince: Long? = null

    /** Milliseconds since the epoch that Krypt trusts; null while automatic date & time is off. */
    @Synchronized
    fun nowMs(): Long? {
        if (!autoTime.isOn()) return null
        val a = anchor ?: loadOrCreateAnchor().also { anchor = it }
        return a.wallMs + (clock.elapsedMs() - a.elapsedMs)
    }

    /** Today in [zone]; null while automatic date & time is off. */
    fun today(zone: ZoneId): LocalDate? =
        nowMs()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /** Milliseconds until the next day starts in [zone]; null while automatic date & time is off. */
    fun msUntilNextDay(zone: ZoneId): Long? {
        val now = nowMs() ?: return null
        val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        return tomorrow.atStartOfDay(zone).toInstant().toEpochMilli() - now
    }

    /**
     * The device clock was set (ACTION_TIME_CHANGED). While automatic time is
     * on that's a network correction, so follow it, even backwards. While it
     * is off it's a change by hand, and ignored - also when it arrives just
     * after automatic time was switched back on, since the broadcast for a
     * change by hand can lag behind that switch. Ignoring a real correction
     * then costs nothing: the anchor kept counting from before.
     */
    @Synchronized
    fun onTimeSet() {
        if (!autoTime.isOn()) return
        val onSince = autoTimeOnSince
        if (onSince != null && clock.elapsedMs() - onSince < SETTLE_MS) return
        val a = Anchor(clock.bootCount(), clock.elapsedMs(), clock.nowMs())
        anchor = a
        save(a, highWaterMs = a.wallMs)
    }

    /** The "Set time automatically" setting changed. */
    @Synchronized
    fun onAutoTimeChanged() {
        autoTimeOnSince = if (autoTime.isOn()) clock.elapsedMs() else null
    }

    /** Remember the trusted time, so after a reboot the day can't go backwards. */
    @Synchronized
    fun checkpoint() {
        val now = nowMs() ?: return
        if (now > prefs.getLong(KEY_HIGH_WATER, Long.MIN_VALUE)) {
            prefs.edit().putLong(KEY_HIGH_WATER, now).apply()
        }
    }

    @VisibleForTesting
    @Synchronized
    fun forget() {
        anchor = null
        autoTimeOnSince = null
        prefs.edit().clear().apply()
    }

    private fun loadOrCreateAnchor(): Anchor {
        val boot = clock.bootCount()
        val elapsedNow = clock.elapsedMs()
        // The saved anchor still holds if it is from this boot: the same boot count
        // or, on a device that doesn't give one, a monotonic clock that hasn't gone back.
        val elapsed = prefs.getLong(KEY_ELAPSED, -1L)
        if (prefs.getInt(KEY_BOOT, NO_BOOT) == (boot ?: NO_BOOT) && elapsed in 0..elapsedNow) {
            return Anchor(boot, elapsed, prefs.getLong(KEY_WALL, 0L))
        }
        // A new boot, or the first use: anchor on the wall clock, never behind what was trusted before.
        val highWater = prefs.getLong(KEY_HIGH_WATER, Long.MIN_VALUE)
        val a = Anchor(boot, elapsedNow, maxOf(clock.nowMs(), highWater))
        save(a, highWaterMs = a.wallMs)
        return a
    }

    private fun save(a: Anchor, highWaterMs: Long) {
        prefs.edit()
            .putInt(KEY_BOOT, a.boot ?: NO_BOOT)
            .putLong(KEY_ELAPSED, a.elapsedMs)
            .putLong(KEY_WALL, a.wallMs)
            .putLong(KEY_HIGH_WATER, highWaterMs)
            .apply()
    }

    private data class Anchor(val boot: Int?, val elapsedMs: Long, val wallMs: Long)

    private companion object {
        const val PREFS_NAME = "krypt_trusted_day"
        const val KEY_BOOT = "boot"
        const val KEY_ELAPSED = "anchor_elapsed"
        const val KEY_WALL = "anchor_wall"
        const val KEY_HIGH_WATER = "high_water"
        const val NO_BOOT = -1

        /** How long after automatic time is switched on a clock change is still treated as by hand. */
        const val SETTLE_MS = 10_000L
    }
}
