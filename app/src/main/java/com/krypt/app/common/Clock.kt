package com.krypt.app.common

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable wall-clock so time-sensitive logic (URL TTL checks, grant
 * expiry) is testable without black-box freezing.
 *
 * Production impl [SystemClock] reads `System.currentTimeMillis()`.
 * Tests can substitute a `FakeClock(var nowMs: Long)` via Hilt
 * `@TestInstallIn`.
 */
interface Clock {
    /** Milliseconds since Unix epoch. */
    fun nowMs(): Long

    /** Seconds since Unix epoch (derived from [nowMs]). */
    fun nowSeconds(): Long = nowMs() / 1000L

    /**
     * Monotonic milliseconds that the user cannot change (time since boot in
     * production). Use for durations that must not stretch when the wall
     * clock is moved, such as unlock grants.
     */
    fun elapsedMs(): Long = nowMs()

    /**
     * Identifies the current boot. [elapsedMs] restarts on every reboot, so
     * two of its readings can only be compared when this matches. Null when
     * the device doesn't say.
     */
    fun bootCount(): Int? = null

    /** The device's time zone. */
    fun zone(): ZoneId = ZoneId.systemDefault()
}

@Singleton
class SystemClock @Inject constructor(
    @ApplicationContext private val context: Context,
) : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedMs(): Long = android.os.SystemClock.elapsedRealtime()
    override fun bootCount(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    } catch (e: SecurityException) {
        null // unknown: callers then treat every boot as new
    }
}
