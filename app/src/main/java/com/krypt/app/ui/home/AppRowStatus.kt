package com.krypt.app.ui.home

import com.krypt.app.data.daily.DailyAccess
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

/** How an app's every-day time stands; its rule runs until [lastDay]. */
sealed interface DailyLine {
    val minutesPerDay: Int
    val lastDay: LocalDate

    data class Left(val minutesLeft: Int, override val minutesPerDay: Int, override val lastDay: LocalDate) : DailyLine
    data class UsedUp(override val minutesPerDay: Int, override val lastDay: LocalDate) : DailyLine
    data class Paused(override val minutesPerDay: Int, override val lastDay: LocalDate) : DailyLine
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
    daily is DailyAccess.Available ->
        AppRowStatus.Daily(DailyLine.Left(wholeMinutes(daily.remainingMs), daily.minutesPerDay, daily.lastDay))
    daily is DailyAccess.UsedUp -> AppRowStatus.Daily(DailyLine.UsedUp(daily.minutesPerDay, daily.lastDay))
    daily is DailyAccess.Paused -> AppRowStatus.Daily(DailyLine.Paused(daily.minutesPerDay, daily.lastDay))
    else -> AppRowStatus.Locked
}

private fun wholeMinutes(ms: Long): Int = ((ms + 59_999) / 60_000).toInt()
