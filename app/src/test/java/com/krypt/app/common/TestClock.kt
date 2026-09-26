package com.krypt.app.common

/**
 * Controllable [Clock] for tests. The wall clock and the monotonic clock
 * can be moved independently, e.g. to simulate the user changing the date.
 */
class TestClock(
    var wallMs: Long = 1_700_000_000_000L,
    var elapsed: Long = 50_000L,
    var boot: Int? = 1,
) : Clock {
    override fun nowMs(): Long = wallMs
    override fun elapsedMs(): Long = elapsed
    override fun bootCount(): Int? = boot

    /** Let [ms] of real time pass: both clocks move. */
    fun advance(ms: Long) {
        wallMs += ms
        elapsed += ms
    }

    /** Reboot, [downMs] of real time later: the monotonic clock starts again near zero. */
    fun reboot(downMs: Long = 60_000L) {
        wallMs += downMs
        elapsed = 1_000L
        boot = boot?.plus(1)
    }
}
