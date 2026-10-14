package com.krypt.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme

/**
 * After too many wrong PINs: a thin ring that empties as the lockout runs
 * out, with the time left inside ("0:28", or "7 min" past 60 s) and one
 * line under it. The line is announced once; the ticking time is not.
 */
@Composable
fun LockoutCountdown(
    remainingMs: Long,
    totalMs: Long,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fraction = if (totalMs <= 0L) 0f else (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
    val sweep by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 1_000, easing = LinearEasing),
        label = "lockoutRing",
    )
    val seconds = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(0)
    val label = if (seconds < 60) {
        "0:" + seconds.toString().padStart(2, '0')
    } else {
        stringResource(R.string.pin_lockout_minutes, (seconds + 59) / 60)
    }
    val a11y = if (seconds < 60) {
        pluralStringResource(R.plurals.pin_lockout_a11y_seconds, seconds, seconds)
    } else {
        val minutes = (seconds + 59) / 60
        pluralStringResource(R.plurals.pin_lockout_a11y_minutes, minutes, minutes)
    }
    // The ring grows with the font so "7 min" still fits at 200% text.
    val ringSize = RING_SIZE * LocalDensity.current.fontScale.coerceAtLeast(1f)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KryptSpace.s4),
    ) {
        Box(
            modifier = Modifier
                .size(ringSize)
                .semantics { contentDescription = a11y },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val stroke = RING_STROKE.toPx()
                val inset = stroke / 2
                drawCircle(
                    color = colors.outlineVariant,
                    radius = size.minDimension / 2 - inset,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = colors.primary,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Text(text = label, style = KryptTheme.text.monoCountdown, color = colors.onSurface)
        }
        Text(
            text = stringResource(R.string.pin_lockout_line),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val RING_SIZE = 120.dp
private val RING_STROKE = 2.dp
