package com.krypt.app.data.daily

import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The Guardian's every-day rule for one app: up to [minutesPerDay] minutes
 * each calendar day from [firstDay] through [lastDay], with days counted in
 * [zone] (the device's zone when the rule was approved, so changing the
 * time zone can't make extra days).
 */
data class DailyAllowance(
    val packageName: String,
    val minutesPerDay: Int,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val zone: ZoneId,
    val requestId: UUID,
    val createdAtMs: Long,
) {
    val dailyMs: Long get() = minutesPerDay * 60_000L

    fun covers(day: LocalDate): Boolean = !day.isBefore(firstDay) && !day.isAfter(lastDay)
}

/** Where an app's every-day rule stands right now. */
sealed interface DailyAccess {
    /** No rule covers today. */
    data object None : DailyAccess

    /** The app may be used for [remainingMs] more today. */
    data class Available(val remainingMs: Long, val minutesPerDay: Int, val lastDay: LocalDate) : DailyAccess

    /** Today's time is used up. */
    data class UsedUp(val minutesPerDay: Int, val lastDay: LocalDate) : DailyAccess

    /** Automatic date & time is off, so today can't be told; the app stays locked. */
    data class Paused(val minutesPerDay: Int, val lastDay: LocalDate) : DailyAccess
}

/** What screens read about every-day rules, and what they can do with them. */
interface DailyAllowances {
    /** Changes whenever what [access] returns may have changed. */
    val changes: StateFlow<Long>

    fun access(pkg: String): DailyAccess

    /** End [pkg]'s rule now; the app asks every time again. */
    suspend fun end(pkg: String)
}
