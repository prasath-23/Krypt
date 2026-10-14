package com.krypt.app.ui.common

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Counts a PIN lockout of [retryInMs] down once a second and returns the
 * milliseconds left; [onFinished] runs when it reaches zero. A null
 * [retryInMs] means no lockout and returns 0. The lockout ring
 * ([com.krypt.app.ui.components.LockoutCountdown]) draws it.
 */
@Composable
fun lockoutCountdown(retryInMs: Long?, onFinished: () -> Unit): Long {
    val finished by rememberUpdatedState(onFinished)
    var leftMs by remember(retryInMs) { mutableLongStateOf(retryInMs ?: 0L) }
    LaunchedEffect(retryInMs) {
        if (retryInMs == null) return@LaunchedEffect
        val endsAt = SystemClock.elapsedRealtime() + retryInMs
        while (true) {
            leftMs = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (leftMs == 0L) break
            delay(minOf(1_000L, leftMs))
        }
        finished()
    }
    return leftMs
}
