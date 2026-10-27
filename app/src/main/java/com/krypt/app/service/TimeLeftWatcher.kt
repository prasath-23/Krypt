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
 * Keeps time for the locked apps on screen (FR-013, Amendments 7 and 8):
 * each is blocked the moment its time ends, and in the last [WARN_MS] of the
 * first to end a countdown shows ([TimeLeftDisplay]).
 *
 * An app's time is what is left of a one-time unlock ([grantMs]), then any
 * every-day time ([usableMs]), which isn't counted while an unlock runs.
 * Only apps on screen are watched ([watch]) - in front, in split screen, in
 * a small window or picture-in-picture: an app in the background needs
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
    private var watched: List<String> = emptyList()

    /** The watched apps that had a one-time unlock running when last looked at. */
    private val granted = HashSet<String>()
    private var timer: Job? = null

    /** Keep time for [pkg] alone, or for no app when it is null. */
    fun watch(pkg: String?) = watch(listOfNotNull(pkg))

    /**
     * Keep time for [pkgs]: the locked apps on screen, with the screen on
     * and unlocked. Call it again whenever their time may have changed.
     */
    fun watch(pkgs: Collection<String>) {
        val apps = pkgs.distinct()
        if (apps != watched) {
            granted.retainAll(apps.toSet())
            watched = apps
        }
        check()
    }

    private fun check() {
        timer?.cancel()
        timer = null
        if (watched.isEmpty()) return display.hide()
        var wait = MAX_WAIT_MS
        var soonest: Pair<String, Long>? = null
        for (pkg in watched) {
            val grant = grantMs(pkg)
            if (pkg in granted && grant <= 0L) onGrantEnded(pkg)
            if (grant > 0L) granted += pkg else granted -= pkg
            val left = usableMs(pkg, grant, WARN_MS + MAX_WAIT_MS)?.let { grant + it }
            if (left != null && left in 1..WARN_MS && (soonest == null || left < soonest.second)) soonest = pkg to left
            if (left != null && left <= 0L) onTimeUp(pkg)
            val appWait = when {
                left == null -> MAX_WAIT_MS
                left <= 0L -> RETRY_MS
                left <= WARN_MS -> (left - 1) % 1_000 + 1 // when the next whole second shows
                else -> minOf(left - WARN_MS, MAX_WAIT_MS)
            }
            wait = minOf(wait, if (grant > 0L) minOf(appWait, grant) else appWait)
        }
        soonest?.let { (pkg, left) -> display.show(pkg, left) } ?: display.hide()
        timer = scope.launch {
            delay(wait)
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
