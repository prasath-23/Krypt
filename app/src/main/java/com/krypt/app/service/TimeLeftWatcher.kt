package com.krypt.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the countdown over the app in front is drawn ([TimeLeftPill]). */
interface TimeLeftDisplay {
    /** Show, or keep showing, that [pkg] has [msLeft] left. */
    fun show(pkg: String, msLeft: Long)

    fun hide()
}

/**
 * Keeps time for the locked app in front (FR-013, Amendment 7): it is
 * blocked the moment its time ends, and in its last [WARN_MS] a countdown
 * shows over it ([TimeLeftDisplay]).
 *
 * Its time is what is left of a one-time unlock ([grantMs]), then any
 * every-day time ([usableMs]), which isn't counted while an unlock runs.
 * Only the app in front is watched ([watch]): an app in the background needs
 * nothing when its time ends, since opening it again is checked anyway.
 *
 * Coroutine timers stop while the phone sleeps, so nothing here waits on one
 * for long: the time left is worked out again from the clocks behind
 * [grantMs] and [usableMs], which keep running, whenever something changes -
 * the app in front, the screen, an unlock, the meter - and at least every
 * [MAX_WAIT_MS] while an app is watched. That is the only timer it keeps,
 * and only while a locked app is open with the screen on; the countdown
 * ticks every second only while it shows.
 *
 * [scope] must be single-threaded (the service uses the main thread).
 */
class TimeLeftWatcher(
    private val scope: CoroutineScope,
    private val grantMs: (pkg: String) -> Long,
    private val usableMs: (pkg: String, afterMs: Long, withinMs: Long) -> Long?,
    private val display: TimeLeftDisplay,
    /** The app's one-time unlock ended while it was watched, so every-day time may start counting. */
    private val onGrantEnded: (pkg: String) -> Unit,
    /** The app has no time left: block it. Asked again every [RETRY_MS] while it stays in front. */
    private val onTimeUp: (pkg: String) -> Unit,
) {
    private var watched: String? = null

    /** [watched] had a one-time unlock running when last looked at. */
    private var granted = false
    private var timer: Job? = null

    /**
     * Keep time for [pkg]: the locked app in front, with the screen on and
     * unlocked. Null when there is none. Call it again whenever the app's
     * time may have changed.
     */
    fun watch(pkg: String?) {
        if (pkg != watched) {
            watched = pkg
            granted = false
        }
        check()
    }

    private fun check() {
        timer?.cancel()
        timer = null
        val pkg = watched ?: return display.hide()
        val grant = grantMs(pkg)
        if (granted && grant <= 0L) onGrantEnded(pkg)
        granted = grant > 0L
        val left = usableMs(pkg, grant, WARN_MS + MAX_WAIT_MS)?.let { grant + it }
        if (left != null && left in 1..WARN_MS) display.show(pkg, left) else display.hide()
        if (left != null && left <= 0L) onTimeUp(pkg)
        val wait = when {
            left == null -> MAX_WAIT_MS
            left <= 0L -> RETRY_MS
            left <= WARN_MS -> (left - 1) % 1_000 + 1 // when the next whole second shows
            else -> minOf(left - WARN_MS, MAX_WAIT_MS)
        }
        timer = scope.launch {
            delay(if (granted) minOf(wait, grant) else wait)
            check()
        }
    }

    companion object {
        /** The countdown shows for the app's last two minutes. */
        const val WARN_MS = 2 * 60_000L

        /** The longest the time left goes unchecked while an app is watched. */
        const val MAX_WAIT_MS = 60_000L

        /** How soon to block again when the app is still in front after its time ended. */
        const val RETRY_MS = 1_000L
    }
}

/**
 * The time left as the countdown shows it, "1:59": minutes and seconds,
 * rounded up, so the last second shows 0:01 and 0:00 never shows while the
 * app is still open.
 */
fun timeLeftText(msLeft: Long): String {
    val seconds = ((msLeft + 999) / 1_000).coerceAtLeast(0L)
    return "${seconds / 60}:" + (seconds % 60).toString().padStart(2, '0')
}
