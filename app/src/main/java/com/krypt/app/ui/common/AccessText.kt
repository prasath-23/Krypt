package com.krypt.app.ui.common

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.krypt.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "30 minutes", "1 hour", "2 hours". */
fun durationText(context: Context, minutes: Int): String {
    val res = context.resources
    return if (minutes % 60 == 0) {
        res.getQuantityString(R.plurals.duration_hours, minutes / 60, minutes / 60)
    } else {
        res.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
    }
}

/** A clock time as the phone shows times, 12- or 24-hour, e.g. "16:00" or "4:00 PM". */
fun clockText(context: Context, time: LocalTime): String {
    val locale = Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
    return time.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
}

/** The clock time [epochMs] shows on this phone, in its time zone, e.g. "16:00" or "4:00 PM". */
fun clockText(context: Context, epochMs: Long): String =
    clockText(context, Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime())

/** A calendar day in the user's short style, e.g. "Sep 28". */
fun dayText(context: Context, day: LocalDate, zone: ZoneId): String = DateUtils.formatDateTime(
    context,
    day.atStartOfDay(zone).toInstant().toEpochMilli(),
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
)
