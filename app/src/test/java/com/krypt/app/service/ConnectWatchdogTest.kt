package com.krypt.app.service

import com.krypt.app.security.FakeSharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Krypt restarts itself when Android starts its accessibility service but never connects it (Amendment 10). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectWatchdogTest {

    private var connected = false
    private var screenInFront = false
    private var restarts = 0
    private var nowMs = 1_800_000_000_000L
    private val prefs = FakeSharedPreferences()

    /** A watchdog as a newly created service makes one; the restart log is kept across them, as on the phone. */
    private fun TestScope.watchdog() = ConnectWatchdog(
        scope = backgroundScope,
        isConnected = { connected },
        screenInFront = { screenInFront },
        restarts = RestartLog(prefs) { nowMs },
        restart = { restarts++ },
    )

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun connectedAtOnce_isLeftAlone() = runTest {
        val w = watchdog()
        w.start()
        pass(500)
        connected = true
        w.stop() // as onServiceConnected does

        pass(ConnectWatchdog.CONNECT_TIMEOUT_MS)

        assertEquals(0, restarts)
    }

    @Test
    fun connectedSlowlyButInTime_isLeftAlone() = runTest {
        watchdog().start()
        pass(ConnectWatchdog.CONNECT_TIMEOUT_MS - 1)
        connected = true

        pass(1)

        assertEquals(0, restarts)
    }

    @Test
    fun neverConnected_restartsKrypt_whenTheTimeIsUp() = runTest {
        // Seen after a reboot under load: the service was created, and Android never connected it.
        watchdog().start()

        pass(ConnectWatchdog.CONNECT_TIMEOUT_MS - 1)
        assertEquals(0, restarts)
        pass(1)

        assertEquals(1, restarts)
    }

    @Test
    fun underOneOfKryptsOwnScreens_waitsUntilItCloses() = runTest {
        screenInFront = true
        watchdog().start()

        pass(ConnectWatchdog.CONNECT_TIMEOUT_MS + 4 * ConnectWatchdog.SCREEN_RETRY_MS)
        assertEquals(0, restarts)
        screenInFront = false
        pass(ConnectWatchdog.SCREEN_RETRY_MS)

        assertEquals(1, restarts)
    }

    @Test
    fun destroyedBeforeTheTimeIsUp_doesNothing() = runTest {
        val w = watchdog()
        w.start()
        pass(10_000)
        w.stop() // as onDestroy does

        pass(ConnectWatchdog.CONNECT_TIMEOUT_MS)

        assertEquals(0, restarts)
    }

    @Test
    fun atMostThreeRestartsAnHour_soAPhoneThatIsOnlySlowCantMakeItLoop() = runTest {
        // Each restart is a new process, with a new service and so a new watchdog.
        repeat(4) {
            watchdog().start()
            pass(ConnectWatchdog.CONNECT_TIMEOUT_MS)
            nowMs += 2 * 60_000L
        }
        assertEquals(3, restarts)

        nowMs += 60 * 60_000L // an hour after the first ones
        watchdog().start()
        pass(ConnectWatchdog.CONNECT_TIMEOUT_MS)

        assertEquals(4, restarts)
    }
}
