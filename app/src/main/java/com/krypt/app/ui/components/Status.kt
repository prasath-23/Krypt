package com.krypt.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.data.daily.DailyAllowance
import com.krypt.app.ui.common.clockText
import com.krypt.app.ui.common.dayText
import com.krypt.app.ui.home.AppRowStatus
import com.krypt.app.ui.home.DailyLine
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme
import java.time.ZoneId

/**
 * The short line under an app's name: always an icon and words, in the
 * colour of its meaning (docs/design-system/components/StatusLine). The
 * number that ticks is set in mono so it doesn't jitter; the line wraps at
 * large font scale and never cuts off the time left.
 *
 * @param endsAt the clock time the access ends, added as " · until 14:35".
 */
@Composable
fun StatusLine(
    status: AppRowStatus,
    modifier: Modifier = Modifier,
    endsAt: String? = null,
) {
    val look = statusLook(status)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s1),
    ) {
        if (look.icon != null) {
            KryptIcon(
                icon = look.icon,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp),
                tint = look.color,
                size = KryptSize.iconSm,
            )
        }
        Text(
            text = statusText(status, endsAt),
            style = MaterialTheme.typography.bodyMedium,
            color = look.color,
        )
    }
}

/** The status line's words, with the ticking number styled in mono. */
@Composable
fun statusText(status: AppRowStatus, endsAt: String? = null): AnnotatedString {
    val mono = SpanStyle(fontFamily = KryptTheme.text.monoTime.fontFamily)
    val line = when (status) {
        AppRowStatus.NotLocked -> AnnotatedString(stringResource(R.string.home_status_not_locked))
        AppRowStatus.Locked -> AnnotatedString(stringResource(R.string.home_status_locked))
        is AppRowStatus.Unlocked -> styledFormat(
            pluralStringResource(R.plurals.home_status_open, status.minutesLeft),
            StyledArg(status.minutesLeft.toString(), mono),
        )
        is AppRowStatus.Daily -> dailyText(status.line, mono)
    }
    if (endsAt == null) return line
    return styledFormat(
        stringResource(R.string.home_status_with_end),
        StyledArg(""),
        StyledArg(endsAt),
    ).let { suffix -> line + suffix }
}

@Composable
private fun dailyText(line: DailyLine, mono: SpanStyle): AnnotatedString {
    val until = dayText(LocalContext.current, line.lastDay, ZoneId.systemDefault())
    if (line.periodMinutes != DailyAllowance.DAY_MINUTES) return periodText(line, until, mono)
    return when (line) {
        is DailyLine.Left -> styledFormat(
            pluralStringResource(R.plurals.home_status_daily_left, line.minutesLeft),
            StyledArg(line.minutesLeft.toString(), mono),
            StyledArg(line.minutes.toString()),
            StyledArg(until),
        )
        is DailyLine.UsedUp -> AnnotatedString(stringResource(R.string.home_status_daily_used_up, until))
        is DailyLine.Paused -> AnnotatedString(stringResource(R.string.home_status_daily_paused))
    }
}

/** Every-day time in periods shorter than a day: "Every 2 h · 20 of 30 min left · until Sep 28". */
@Composable
private fun periodText(line: DailyLine, until: String, mono: SpanStyle): AnnotatedString {
    val minutes = line.periodMinutes
    val period = when {
        minutes == 60 -> stringResource(R.string.home_period_hourly)
        minutes < 60 -> stringResource(R.string.home_period_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.home_period_hours, minutes / 60)
        else -> stringResource(R.string.home_period_hours_minutes, minutes / 60, minutes % 60)
    }
    return when (line) {
        is DailyLine.Left -> styledFormat(
            pluralStringResource(R.plurals.home_status_period_left, line.minutesLeft),
            StyledArg(period),
            StyledArg(line.minutesLeft.toString(), mono),
            StyledArg(line.minutes.toString()),
            StyledArg(until),
        )
        is DailyLine.UsedUp -> {
            val backAt = line.backAtMs
            if (backAt != null) {
                styledFormat(
                    stringResource(R.string.home_status_period_used_up),
                    StyledArg(period),
                    StyledArg(clockText(LocalContext.current, backAt), mono),
                    StyledArg(until),
                )
            } else {
                AnnotatedString(stringResource(R.string.home_status_period_done, period, until))
            }
        }
        is DailyLine.Paused -> AnnotatedString(stringResource(R.string.home_status_period_paused, period))
    }
}

/** How a status looks: its icon (none for Not locked) and the colour of its meaning. */
data class StatusLook(@DrawableRes val icon: Int?, val color: Color)

@Composable
fun statusLook(status: AppRowStatus): StatusLook {
    val krypt = KryptTheme.colors
    return when (status) {
        AppRowStatus.NotLocked -> StatusLook(null, MaterialTheme.colorScheme.onSurfaceVariant)
        AppRowStatus.Locked -> StatusLook(KryptIcons.Lock, krypt.locked)
        is AppRowStatus.Unlocked -> StatusLook(KryptIcons.LockOpen, krypt.open)
        is AppRowStatus.Daily -> when (status.line) {
            is DailyLine.Left -> StatusLook(KryptIcons.LockClock, krypt.open)
            is DailyLine.UsedUp -> StatusLook(KryptIcons.HourglassBottom, krypt.caution)
            is DailyLine.Paused -> StatusLook(KryptIcons.Schedule, krypt.caution)
        }
    }
}

/** A fully round count badge for Home's header, e.g. "42 locked" or "2 open now". */
@Composable
fun CountBadge(
    text: String,
    @DrawableRes icon: Int,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .heightIn(min = 24.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s1),
    ) {
        KryptIcon(icon, contentDescription = null, tint = contentColor, size = KryptSize.iconSm)
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}
