package com.krypt.app.ui.common

import android.content.Context
import android.text.format.DateUtils
import com.krypt.app.R
import java.time.LocalDate
import java.time.ZoneId

/** "30 minutes", "1 hour", "2 hours". */
fun durationText(context: Context, minutes: Int): String {
    val res = context.resources
    return if (minutes % 60 == 0) {
        res.getQuantityString(R.plurals.duration_hours, minutes / 60, minutes / 60)
    } else {
        res.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
    }
}

/** A calendar day in the user's short style, e.g. "Sep 28". */
fun dayText(context: Context, day: LocalDate, zone: ZoneId): String = DateUtils.formatDateTime(
    context,
    day.atStartOfDay(zone).toInstant().toEpochMilli(),
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
)
