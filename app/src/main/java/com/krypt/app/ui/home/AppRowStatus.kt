package com.krypt.app.ui.home

import com.krypt.app.data.daily.DailyAccess
import com.krypt.app.data.daily.DailyAllowance
import java.time.LocalDate

/**
 * What one Home Screen row says about its app.
 *
 * A Guardian unlock is temporary: the app stays locked (its switch stays
 * on) while it is [Unlocked], and it locks again by itself when the time
 * runs out.
 */
sealed interface AppRowStatus {
    /** Krypt doesn't lock this app. */
    data object NotLocked : AppRowStatus

    /** Locked: opening it asks the Guardian. */
    data object Locked : AppRowStatus

    /** Locked, but the Guardian unlocked it for [minutesLeft] more minutes (rounded up). */
    data class Unlocked(val minutesLeft: Int) : AppRowStatus

    /** Locked, with the Guardian's every-day time. */
    data class Daily(val line: DailyLine) : AppRowStatus
}

/** The app can be used right now: a Guardian unlock is running, or daily time is left today. */
val AppRowStatus.isOpenNow: Boolean
    get() = this is AppRowStatus.Unlocked || (this is AppRowStatus.Daily && line is DailyLine.Left)

/**
 * How an app's every-day time stands: [minutes] in each [periodMinutes]-minute
 * period (1440: a day), under a rule that runs until [lastDay].
 */
sealed interface DailyLine {
    val minutes: Int
    val lastDay: LocalDate
    val periodMinutes: Int

    data class Left(
        val minutesLeft: Int,
        override val minutes: Int,
        override val lastDay: LocalDate,
        override val periodMinutes: Int = DailyAllowance.DAY_MINUTES,
    ) : DailyLine

    /**
     * Used up for this period; [backAtMs] is when the next one starts, in
     * milliseconds since the epoch (null for a day, or when the rule ends first).
     */
    data class UsedUp(
        override val minutes: Int,
        override val lastDay: LocalDate,
        override val periodMinutes: Int = DailyAllowance.DAY_MINUTES,
        val backAtMs: Long? = null,
    ) : DailyLine

    data class Paused(
        override val minutes: Int,
        override val lastDay: LocalDate,
        override val periodMinutes: Int = DailyAllowance.DAY_MINUTES,
    ) : DailyLine
}

/**
 * @param grantRemainingMs time left on the app's Guardian unlock, as
 *   [com.krypt.app.data.LockerSessionStore.remainingMs] reports it; 0 when it has none.
 * @param daily the app's every-day time; a running one-time unlock is shown instead.
 */
fun appRowStatus(
    isLocked: Boolean,
    grantRemainingMs: Long,
    daily: DailyAccess = DailyAccess.None,
): AppRowStatus = when {
    !isLocked -> AppRowStatus.NotLocked
    grantRemainingMs > 0 -> AppRowStatus.Unlocked(minutesLeft = wholeMinutes(grantRemainingMs))
    daily is DailyAccess.Available -> AppRowStatus.Daily(
        DailyLine.Left(wholeMinutes(daily.remainingMs), daily.minutes, daily.lastDay, daily.periodMinutes),
    )
    daily is DailyAccess.UsedUp ->
        AppRowStatus.Daily(DailyLine.UsedUp(daily.minutes, daily.lastDay, daily.periodMinutes, daily.backAtMs))
    daily is DailyAccess.Paused -> AppRowStatus.Daily(DailyLine.Paused(daily.minutes, daily.lastDay, daily.periodMinutes))
    else -> AppRowStatus.Locked
}

private fun wholeMinutes(ms: Long): Int = ((ms + 59_999) / 60_000).toInt()
