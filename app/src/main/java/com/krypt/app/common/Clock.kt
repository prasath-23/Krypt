package com.krypt.app.common

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
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
