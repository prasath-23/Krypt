package com.krypt.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KryptDatabaseTest {

    private lateinit var db: KryptDatabase
    private lateinit var lockedApps: LockedAppDao
    private lateinit var pairing: GuardianPairingDao
    private lateinit var outstanding: OutstandingRequestDao
    private lateinit var grants: UnlockGrantDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, KryptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        lockedApps = db.lockedAppDao()
        pairing = db.guardianPairingDao()
        outstanding = db.outstandingRequestDao()
        grants = db.unlockGrantDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun lockedAppRoundTrip() = runBlocking {
        val app = LockedAppEntity(
            packageName = "com.example.app",
            displayName = "Example",
            lockState = LockState.LOCKED,
            lockSource = LockSource.DEFAULT_DENY,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )
        lockedApps.insert(app)
        assertEquals(app, lockedApps.findByPackage("com.example.app"))
        lockedApps.updateLockState("com.example.app", LockState.UNLOCKED, 2_000L)
        assertEquals(LockState.UNLOCKED, lockedApps.findByPackage("com.example.app")?.lockState)
    }

    @Test
    fun guardianPairingEnforcesSingleRow() = runBlocking {
        val a = GuardianPairingEntity(
            role = PairingRole.SUBJECT_OF_GUARDIAN,
            remoteDisplayName = "A",
            pubSalt = ByteArray(32) { 0 },
            kdfIterations = 300_000,
            pairedAt = 1_000,
        )
        val b = GuardianPairingEntity(
            role = PairingRole.SUBJECT_OF_GUARDIAN,
            remoteDisplayName = "B",
            pubSalt = ByteArray(32) { 1 },
            kdfIterations = 400_000,
            pairedAt = 2_000,
        )
        pairing.upsert(a)
        pairing.upsert(b)
        assertEquals(b, pairing.getPairing())
    }

    @Test
    fun outstandingRequestMarkConsumedIfOpenAtomic() = runBlocking {
        val id = UUID.randomUUID().toString()
        val req = OutstandingRequestEntity(
            requestId = id,
            targetPackage = "com.example.app",
            salt = ByteArray(16),
            issuedAt = 1_000,
            expiresAt = 2_000,
            consumed = 0,
        )
        outstanding.insert(req)
        assertEquals(1, outstanding.markConsumedIfOpen(id, now = 1_500))
        assertEquals(0, outstanding.markConsumedIfOpen(id, now = 1_500)) // already consumed
        assertNotNull(outstanding.findById(id)?.takeIf { it.consumed == 1 })
    }

    @Test
    fun outstandingRequestMarkConsumedIfOpenRejectsExpired() = runBlocking {
        val id = UUID.randomUUID().toString()
        outstanding.insert(
            OutstandingRequestEntity(
                requestId = id,
                targetPackage = "com.example.app",
                salt = ByteArray(16),
                issuedAt = 1_000,
                expiresAt = 2_000,
                consumed = 0,
            )
        )
        assertEquals(0, outstanding.markConsumedIfOpen(id, now = 3_000)) // expired
    }

    @Test
    fun concurrentMarkConsumedIfOpenProducesExactlyOneSuccess() = runBlocking {
        val id = UUID.randomUUID().toString()
        outstanding.insert(
            OutstandingRequestEntity(
                requestId = id,
                targetPackage = "com.example.app",
                salt = ByteArray(16),
                issuedAt = 1_000,
                expiresAt = 10_000,
                consumed = 0,
            )
        )
        val results = (1..10).map {
            async { outstanding.markConsumedIfOpen(id, now = 5_000) }
        }.awaitAll()
        assertEquals(1, results.count { it == 1 })
        assertEquals(9, results.count { it == 0 })
    }

    @Test
    fun unlockGrantActiveFilterRespectsExpiry() = runBlocking {
        val id = grants.insert(
            UnlockGrantEntity(
                requestId = "req1",
                targetPackage = "com.example.app",
                grantedAt = 1_000,
                expiresAt = 5_000,
            )
        )
        assertEquals(id, grants.activeGrantForPackage("com.example.app", now = 4_000)?.id)
        assertNull(grants.activeGrantForPackage("com.example.app", now = 6_000))
    }

    @Test
    fun unlockGrantObserveActiveFlowEmitsInserts() = runBlocking {
        grants.insert(
            UnlockGrantEntity(
                requestId = "req1",
                targetPackage = "com.example.app",
                grantedAt = 1_000,
                expiresAt = 5_000,
            )
        )
        val active = grants.observeActiveAt(now = 2_000).first()
        assertEquals(1, active.size)
    }
}
