package com.krypt.app.data

import com.krypt.app.common.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockerSessionStoreTest {

    private val clock = object : Clock {
        var now: Long = 1_000L
        override fun nowMs(): Long = now
    }

    @Test
    fun isUnlockedNowRespectsExpiry() {
        val store = LockerSessionStore(clock)
        store.recordGrant("com.example.app", expiresAtMs = 2_000L)
        assertTrue(store.isUnlockedNow("com.example.app"))
        clock.now = 3_000L
        assertFalse(store.isUnlockedNow("com.example.app"))
    }

    @Test
    fun unknownPackageIsLocked() {
        val store = LockerSessionStore(clock)
        assertFalse(store.isUnlockedNow("com.other.app"))
    }

    @Test
    fun expireAllClearsAllEntries() {
        val store = LockerSessionStore(clock)
        store.recordGrant("a", 5_000L)
        store.recordGrant("b", 5_000L)
        store.expireAll()
        assertEquals(0, store.approximateSize())
    }

    @Test
    fun concurrentWritesAndReadsNoCrash() = runBlocking(Dispatchers.Default) {
        val store = LockerSessionStore(clock)
        val jobs = (1..1000).map { i ->
            async {
                if (i % 2 == 0) store.recordGrant("pkg.$i", 10_000L)
                else store.isUnlockedNow("pkg.$i")
            }
        }
        jobs.awaitAll()
        // No exception thrown; size is bounded.
        assertTrue(store.approximateSize() <= 500)
    }
}
