package com.krypt.app.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.krypt.app.common.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brute-force brake shared by every screen that checks the Guardian PIN on
 * this device: the Krypt entry screen and the Guardian approval screen. It
 * is persisted, so switching screens or restarting Krypt gives no extra
 * attempts, and a correct PIN on either screen resets it.
 *
 * The 1st and 2nd wrong PINs are free; the 3rd locks for 30 s, the 4th-5th
 * for 2 min, the 6th-7th for 10 min, and every later one for an hour.
 * Lockouts are timed on [Clock.elapsedMs], so moving the device clock
 * forward does not end them early. A reboot restarts that clock, and a
 * lockout still running then starts over: the wall clock can't be trusted
 * to say how much of it was served.
 */
@Singleton
class PinAttemptLimiter internal constructor(
    private val prefs: SharedPreferences,
    private val clock: Clock,
) {

    @Inject constructor(@ApplicationContext context: Context, clock: Clock) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), clock)

    /** Milliseconds until the next PIN may be tried; 0 when one may be tried now. */
    @Synchronized
    fun lockedForMs(): Long {
        val lockMs = prefs.getLong(KEY_LOCK_MS, 0L)
        if (lockMs <= 0L) return 0L
        val elapsedNow = clock.elapsedMs()
        val startedAt = prefs.getLong(KEY_LOCK_STARTED_ELAPSED, 0L)
        if (elapsedNow < startedAt) {
            // Rebooted mid-lockout: start it over on the new boot's clock.
            prefs.edit().putLong(KEY_LOCK_STARTED_ELAPSED, elapsedNow).apply()
            return lockMs
        }
        val left = lockMs - (elapsedNow - startedAt)
        if (left > 0L) return left
        // Served in full; forget it so a later reboot can't restart it.
        prefs.edit().remove(KEY_LOCK_MS).remove(KEY_LOCK_STARTED_ELAPSED).apply()
        return 0L
    }

    /**
     * Call just before checking a PIN. The check is slow (PBKDF2), so the
     * attempt counts as a wrong PIN from the start - leaving Krypt mid-check
     * must not make a guess free - until [recordSuccess] clears the count.
     *
     * Returns how many more wrong PINs are allowed before a lockout if this
     * one is wrong; 0 means a wrong PIN here starts a lockout.
     */
    @SuppressLint("ApplySharedPref") // see the commit() below
    @Synchronized
    fun beginAttempt(): Int {
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILURES, failures)
        val lockMs = lockoutFor(failures)
        if (lockMs > 0L) {
            editor.putLong(KEY_LOCK_MS, lockMs)
                .putLong(KEY_LOCK_STARTED_ELAPSED, clock.elapsedMs())
        }
        editor.commit() // on disk before the check starts, in case Krypt is killed during it
        return (FIRST_LOCKOUT_AT - failures).coerceAtLeast(0)
    }

    /**
     * Take back the attempt just begun: it turned out not to be a guess,
     * because no PIN is set up to check it against.
     */
    @SuppressLint("ApplySharedPref") // must not come back as a failure after a restart
    @Synchronized
    fun cancelAttempt() {
        val failures = prefs.getInt(KEY_FAILURES, 0)
        if (failures <= 0) return
        // An attempt only begins when no lockout is running, so any lockout
        // on record now is the one it started.
        prefs.edit()
            .putInt(KEY_FAILURES, failures - 1)
            .remove(KEY_LOCK_MS)
            .remove(KEY_LOCK_STARTED_ELAPSED)
            .commit()
    }

    /** A correct PIN: clears the count, including the attempt counted up front. */
    @SuppressLint("ApplySharedPref") // or a restart could bring back that attempt's lockout
    @Synchronized
    fun recordSuccess() {
        prefs.edit().clear().commit()
    }

    private fun lockoutFor(failures: Int): Long = when {
        failures < FIRST_LOCKOUT_AT -> 0L
        failures == FIRST_LOCKOUT_AT -> 30_000L
        failures <= 5 -> 2 * 60_000L
        failures <= 7 -> 10 * 60_000L
        else -> 60 * 60_000L
    }

    companion object {
        /** The wrong PIN that triggers the first lockout. */
        const val FIRST_LOCKOUT_AT = 3

        private const val PREFS_NAME = "krypt_pin_attempts"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LOCK_MS = "lock_ms"
        private const val KEY_LOCK_STARTED_ELAPSED = "lock_started_elapsed"
    }
}
