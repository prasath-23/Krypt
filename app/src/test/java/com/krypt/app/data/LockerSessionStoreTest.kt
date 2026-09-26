package com.krypt.app.data

import app.cash.turbine.test
import com.krypt.app.common.Clock
import com.krypt.app.common.TestClock
import com.krypt.app.security.FakeSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockerSessionStoreTest {

    private val clock = object : Clock {
        var now: Long = 1_000L
        override fun nowMs(): Long = now
    }

    /** Survives [store] instances, like the real prefs survive a process restart. */
    private val prefs = FakeSharedPreferences()

    private fun store(clock: Clock = this.clock) = LockerSessionStore(prefs, clock)

    /** A new process: a fresh store that restores what the last one saved. */
    private fun restarted(clock: Clock) = store(clock).apply { restore() }

    @Test
    fun isUnlockedNowRespectsExpiry() {
        val store = store()
        store.recordGrant("com.example.app", expiresAtMs = 2_000L)
        assertTrue(store.isUnlockedNow("com.example.app"))
        clock.now = 3_000L
        assertFalse(store.isUnlockedNow("com.example.app"))
    }

    @Test
    fun recordGrantAnnouncesThePackage() = runTest {
        val store = store()
        store.grantEvents.test {
            store.recordGrant("com.example.app", expiresAtMs = 2_000L)
            assertEquals("com.example.app", awaitItem())
        }
    }

    @Test
    fun unknownPackageIsLocked() {
        val store = store()
        assertFalse(store.isUnlockedNow("com.other.app"))
    }

    @Test
    fun expireAllClearsAllEntries() {
        val store = store()
        store.recordGrant("a", 5_000L)
        store.recordGrant("b", 5_000L)
        store.expireAll()
        assertEquals(0, store.approximateSize())
    }

    @Test
    fun concurrentWritesAndReadsNoCrash() = runBlocking(Dispatchers.Default) {
        val store = store(TestClock())
        val jobs = (1..1000).map { i ->
            async {
                if (i % 2 == 0) store.recordGrant("pkg.$i", 1_700_000_010_000L)
                else store.isUnlockedNow("pkg.$i")
            }
        }
        jobs.awaitAll()
        // No exception thrown; size is bounded.
        assertTrue(store.approximateSize() <= 500)
    }

    @Test
    fun remainingMsCountsDownAndGrantedPackagesListsActiveOnly() {
        val testClock = TestClock()
        val store = store(testClock)
        store.recordGrant("com.a", testClock.wallMs + 60_000)
        store.recordGrant("com.b", testClock.wallMs + 10_000)

        testClock.advance(15_000)

        assertEquals(45_000L, store.remainingMs("com.a"))
        assertEquals(0L, store.remainingMs("com.b"))
        assertEquals(setOf("com.a"), store.grantedPackages())
    }

    @Test
    fun movingTheWallClockBackDoesNotStretchAGrant() {
        val testClock = TestClock()
        val store = store(testClock)
        store.recordGrant("com.a", expiresAtMs = testClock.wallMs + 15 * 60_000)

        testClock.wallMs -= 60 * 60_000L // user sets the date back an hour
        testClock.elapsed += 15 * 60_000L // ...and 15 real minutes pass

        assertFalse(store.isUnlockedNow("com.a"))
    }

    @Test
    fun recordGrantNeverShortensAnExistingGrant() {
        val testClock = TestClock()
        val store = store(testClock)
        store.recordGrant("com.a", testClock.wallMs + 30 * 60_000)

        store.recordGrant("com.a", testClock.wallMs + 5 * 60_000)

        assertEquals(30 * 60_000L, store.remainingMs("com.a"))
    }

    @Test
    fun aRestartKeepsTheTimeLeft() {
        val testClock = TestClock()
        store(testClock).recordGrant("com.a", testClock.wallMs + 15 * 60_000)
        testClock.advance(5 * 60_000)

        assertEquals(10 * 60_000L, restarted(testClock).remainingMs("com.a"))
    }

    @Test
    fun aRebootEndsEveryGrant() {
        val testClock = TestClock()
        store(testClock).recordGrant("com.a", testClock.wallMs + 15 * 60_000)

        testClock.reboot(downMs = 60_000)

        assertFalse(restarted(testClock).isUnlockedNow("com.a"))
    }

    @Test
    fun settingTheDateBackThenRestarting_doesNotReviveAnEndedGrant() {
        val testClock = TestClock()
        store(testClock).recordGrant("com.a", testClock.wallMs + 15 * 60_000)
        testClock.advance(15 * 60_000) // the grant runs out

        testClock.wallMs -= 60 * 60_000L // the user sets the date back an hour

        assertFalse(restarted(testClock).isUnlockedNow("com.a"))
    }

    @Test
    fun settingTheDateBackThenRestarting_givesNoExtraTime() {
        val testClock = TestClock()
        store(testClock).recordGrant("com.a", testClock.wallMs + 15 * 60_000)
        testClock.advance(5 * 60_000)

        testClock.wallMs -= 60 * 60_000L

        assertEquals(10 * 60_000L, restarted(testClock).remainingMs("com.a"))
    }

    @Test
    fun aRevokedGrantStaysRevokedAfterARestart() {
        val testClock = TestClock()
        val store = store(testClock)
        store.recordGrant("com.a", testClock.wallMs + 15 * 60_000)
        store.recordGrant("com.b", testClock.wallMs + 15 * 60_000)

        store.revoke("com.a")
        val restarted = restarted(testClock)

        assertFalse(restarted.isUnlockedNow("com.a"))
        assertTrue(restarted.isUnlockedNow("com.b"))
    }

    @Test
    fun expireAllAlsoEndsSavedGrants() {
        val testClock = TestClock()
        val store = store(testClock)
        store.recordGrant("com.a", testClock.wallMs + 15 * 60_000)

        store.expireAll()

        assertFalse(restarted(testClock).isUnlockedNow("com.a"))
    }

    @Test
    fun withoutABootCount_grantsAreNotRestored() {
        val testClock = TestClock(boot = null)
        store(testClock).recordGrant("com.a", testClock.wallMs + 15 * 60_000)

        assertFalse(restarted(testClock).isUnlockedNow("com.a"))
    }

    @Test
    fun grantsFromBeforeARebootNeverComeBackWithANewOne() {
        val testClock = TestClock()
        testClock.elapsed = 5 * 60 * 60_000L // up for 5 h, so this grant ends at 5h15m of uptime
        store(testClock).recordGrant("com.old", testClock.wallMs + 15 * 60_000)
        testClock.reboot()

        store(testClock).recordGrant("com.new", testClock.wallMs + 15 * 60_000) // no restore() first
        val restarted = restarted(testClock)

        assertFalse(restarted.isUnlockedNow("com.old"))
        assertTrue(restarted.isUnlockedNow("com.new"))
    }

    @Test
    fun restoreAnnouncesRestoredGrants() = runTest {
        val testClock = TestClock()
        store(testClock).recordGrant("com.a", testClock.wallMs + 60_000)
        val restarted = store(testClock)

        restarted.grantEvents.test {
            restarted.restore()
            assertEquals("com.a", awaitItem())
        }
    }
}
