package com.krypt.app.service

import com.krypt.app.common.Clock
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.security.FakeSharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GrantExpiryWatcherTest {

    private val expired = mutableListOf<String>()

    /** A store on the test scheduler's virtual clock, watched from [TestScope.backgroundScope]. */
    private fun TestScope.watchedStore(beforeStart: (LockerSessionStore) -> Unit = {}): LockerSessionStore {
        val clock = object : Clock {
            override fun nowMs() = WALL_START + testScheduler.currentTime
            override fun elapsedMs() = testScheduler.currentTime
        }
        val store = LockerSessionStore(FakeSharedPreferences(), clock)
        beforeStart(store)
        GrantExpiryWatcher(backgroundScope, store) { expired += it }.start()
        return store
    }

    @Test
    fun grantRecordedAfterStart_firesOnceWhenItRunsOut() = runTest {
        val store = watchedStore()
        store.recordGrant("com.a", WALL_START + 60_000)

        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(emptyList<String>(), expired)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("com.a"), expired)

        advanceTimeBy(10 * 60_000)
        assertEquals("fires only once", listOf("com.a"), expired)
    }

    @Test
    fun grantActiveBeforeStart_isWatched() = runTest {
        watchedStore { it.recordGrant("com.a", WALL_START + 30_000) }

        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(listOf("com.a"), expired)
    }

    @Test
    fun extendedGrant_firesOnlyAtTheLaterExpiry() = runTest {
        val store = watchedStore()
        store.recordGrant("com.a", WALL_START + 60_000)
        advanceTimeBy(30_000)
        runCurrent()

        store.recordGrant("com.a", WALL_START + 30_000 + 120_000)
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals("not at the first expiry", emptyList<String>(), expired)

        advanceTimeBy(80_001)
        runCurrent()
        assertEquals(listOf("com.a"), expired)
    }

    @Test
    fun grantsForDifferentAppsFireIndependently() = runTest {
        val store = watchedStore()
        store.recordGrant("com.a", WALL_START + 60_000)
        store.recordGrant("com.b", WALL_START + 20_000)

        advanceTimeBy(20_001)
        runCurrent()
        assertEquals(listOf("com.b"), expired)

        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(listOf("com.b", "com.a"), expired)
    }

    @Test
    fun noGrant_neverFires() = runTest {
        watchedStore()

        advanceTimeBy(24 * 60 * 60_000L)

        assertEquals(emptyList<String>(), expired)
    }

    private companion object {
        const val WALL_START = 1_700_000_000_000L
    }
}
