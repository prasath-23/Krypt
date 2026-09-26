package com.krypt.app.ui.home

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
}

/**
 * @param grantRemainingMs time left on the app's Guardian unlock, as
 *   [com.krypt.app.data.LockerSessionStore.remainingMs] reports it; 0 when it has none.
 */
fun appRowStatus(isLocked: Boolean, grantRemainingMs: Long): AppRowStatus = when {
    !isLocked -> AppRowStatus.NotLocked
    grantRemainingMs > 0 -> AppRowStatus.Unlocked(minutesLeft = ((grantRemainingMs + 59_999) / 60_000).toInt())
    else -> AppRowStatus.Locked
}
