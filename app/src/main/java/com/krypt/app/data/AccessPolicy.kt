package com.krypt.app.data

import com.krypt.app.data.daily.DailyAccess
import java.time.LocalDate

/**
 * The one rule for whether an app may be used right now. Everything that
 * can block an app - opening it, a grant running out, today's time running
 * out, the screen coming back on - goes through [decide].
 */
object AccessPolicy {

    fun decide(isLocked: Boolean, hasGrant: Boolean, daily: DailyAccess): AccessDecision = when {
        !isLocked -> AccessDecision.Allow
        hasGrant -> AccessDecision.Allow
        daily is DailyAccess.Available -> AccessDecision.Allow
        daily is DailyAccess.UsedUp -> AccessDecision.Block(LockReason.DailyUsedUp(daily.minutesPerDay, daily.lastDay))
        daily is DailyAccess.Paused -> AccessDecision.Block(LockReason.DailyPaused(daily.minutesPerDay, daily.lastDay))
        else -> AccessDecision.Block(LockReason.Locked)
    }
}

sealed interface AccessDecision {
    data object Allow : AccessDecision
    data class Block(val reason: LockReason) : AccessDecision
}

/** Why an app is blocked, as the lock screen explains it. */
sealed interface LockReason {
    /** Locked: ask the Guardian. */
    data object Locked : LockReason

    /** Today's every-day time is used up; it comes back tomorrow (until [lastDay]). */
    data class DailyUsedUp(val minutesPerDay: Int, val lastDay: LocalDate) : LockReason

    /** Every-day time is paused because automatic date & time is off. */
    data class DailyPaused(val minutesPerDay: Int, val lastDay: LocalDate) : LockReason
}
