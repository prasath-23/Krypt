package com.krypt.app.ui.guardian

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RateLimiter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("krypt_rate_limit", Context.MODE_PRIVATE)

    sealed interface AttemptResult {
        data object Allowed : AttemptResult
        data class LockedUntil(val retryAtMs: Long) : AttemptResult
    }

    @Synchronized
    fun tryAttempt(nowMs: Long = System.currentTimeMillis()): AttemptResult {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        return if (nowMs < lockedUntil) AttemptResult.LockedUntil(lockedUntil)
        else AttemptResult.Allowed
    }

    @Synchronized
    fun recordFailure(nowMs: Long = System.currentTimeMillis()) {
        val count = prefs.getInt(KEY_FAILURE_COUNT, 0) + 1
        val lockMs = when {
            count <= 2 -> 0L
            count == 3 -> 30_000L
            count <= 5 -> 2 * 60_000L
            count <= 7 -> 10 * 60_000L
            else -> 60 * 60_000L
        }
        prefs.edit()
            .putInt(KEY_FAILURE_COUNT, count)
            .putLong(KEY_LOCKED_UNTIL, nowMs + lockMs)
            .apply()
    }

    @Synchronized
    fun recordSuccess() {
        prefs.edit()
            .putInt(KEY_FAILURE_COUNT, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .apply()
    }

    private companion object {
        const val KEY_FAILURE_COUNT = "failure_count"
        const val KEY_LOCKED_UNTIL = "locked_until"
    }
}
