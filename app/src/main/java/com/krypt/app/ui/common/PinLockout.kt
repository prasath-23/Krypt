package com.krypt.app.ui.common

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.krypt.app.R
import kotlinx.coroutines.delay

/**
 * Counts a PIN lockout of [retryInMs] down once a second and returns the
 * milliseconds left; [onFinished] runs when it reaches zero. A null
 * [retryInMs] means no lockout and returns 0.
 */
@Composable
fun lockoutCountdown(retryInMs: Long?, onFinished: () -> Unit): Long {
    var leftMs by remember(retryInMs) { mutableLongStateOf(retryInMs ?: 0L) }
    LaunchedEffect(retryInMs) {
        if (retryInMs == null) return@LaunchedEffect
        val endsAt = SystemClock.elapsedRealtime() + retryInMs
        while (true) {
            leftMs = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (leftMs == 0L) break
            delay(minOf(1_000L, leftMs))
        }
        onFinished()
    }
    return leftMs
}

/** "Too many wrong PINs. Try again in N s / N min." */
@Composable
fun pinLockoutMessage(leftMs: Long): String {
    val seconds = ((leftMs + 999) / 1000).coerceAtLeast(1)
    return if (seconds < 60) {
        stringResource(R.string.pin_error_lockout_seconds, seconds.toInt())
    } else {
        stringResource(R.string.pin_error_lockout_minutes, ((seconds + 59) / 60).toInt())
    }
}
