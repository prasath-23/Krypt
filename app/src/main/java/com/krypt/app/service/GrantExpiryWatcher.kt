package com.krypt.app.service

import com.krypt.app.data.LockerSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [onExpired] with the package name when an unlock grant runs out, so
 * an app that is still open when its grant ends can be blocked right away
 * rather than at its next window change (FR-013).
 *
 * Watches grants that are already active when [start] is called as well as
 * ones recorded later. A grant that is extended before it runs out fires
 * only once, at the later expiry. [scope] must be single-threaded (the
 * service uses the main thread).
 */
class GrantExpiryWatcher(
    private val scope: CoroutineScope,
    private val sessionStore: LockerSessionStore,
    private val onExpired: (String) -> Unit,
) {
    private val timers = HashMap<String, Job>()

    fun start() {
        // Subscribe before taking the snapshot so no grant slips between them.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionStore.grantEvents.collect(::watch)
        }
        sessionStore.grantedPackages().forEach(::watch)
    }

    private fun watch(pkg: String) {
        timers.remove(pkg)?.cancel()
        timers[pkg] = scope.launch {
            while (true) {
                val remaining = sessionStore.remainingMs(pkg)
                if (remaining <= 0) break
                delay(remaining)
            }
            timers.remove(pkg)
            onExpired(pkg)
        }
    }
}
