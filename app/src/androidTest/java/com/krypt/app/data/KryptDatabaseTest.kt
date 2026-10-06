package com.krypt.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.data.daily.DailyAllowance
import com.krypt.app.data.daily.DailyAllowanceEntity
import com.krypt.app.data.daily.DailyUsageEntity
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
import java.time.LocalDate
import java.time.ZoneId
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
    fun dailyAllowances_oneRulePerApp_andANewOneReplacesTheOld() = runBlocking {
        val daily = db.dailyAllowanceDao()
        daily.upsert(rule("com.a", minutes = 60))
        daily.upsert(rule("com.a", minutes = 30))
        daily.upsert(rule("com.b", minutes = 15))

        assertEquals(mapOf("com.a" to 30, "com.b" to 15), daily.all().associate { it.packageName to it.minutesPerDay })
        daily.delete("com.a")
        assertEquals(listOf("com.b"), daily.all().map { it.packageName })
    }

    @Test
    fun dailyAllowances_pruneDropsRulesThatEnded() = runBlocking {
        val daily = db.dailyAllowanceDao()
        daily.upsert(rule("com.a", lastDay = 100))
        daily.upsert(rule("com.b", lastDay = 200))

        assertEquals(1, daily.pruneEndedBefore(150))
        assertEquals(listOf("com.b"), daily.all().map { it.packageName })
    }

    @Test
    fun dailyUsage_isKeptPerAppPerDay() = runBlocking {
        val usage = db.dailyUsageDao()
        usage.upsert(DailyUsageEntity("com.a", 100, 1_000))
        usage.upsert(DailyUsageEntity("com.a", 101, 2_000))
        usage.upsert(DailyUsageEntity("com.a", 100, 3_000))

        assertEquals(3_000L, usage.usedMs("com.a", 100))
        assertEquals(2_000L, usage.usedMs("com.a", 101))
        assertNull(usage.usedMs("com.b", 100))
        assertEquals(1, usage.pruneBefore(101))
    }

    @Test
    fun dailyUsage_pruneKeepsDaysAnAppsRuleCovers() = runBlocking {
        db.dailyAllowanceDao().upsert(rule("com.a")) // covers day 100 onwards
        val usage = db.dailyUsageDao()
        usage.upsert(DailyUsageEntity("com.a", 99, 1_000)) // before the rule
        usage.upsert(DailyUsageEntity("com.a", 100, 2_000))
        usage.upsert(DailyUsageEntity("com.b", 100, 3_000)) // no rule

        assertEquals(2, usage.pruneBefore(150))
        assertEquals(listOf("com.a" to 100L), usage.all().map { it.packageName to it.epochDay })
    }

    @Test
    fun consumingADailyApproval_isSingleUse_andMakesNoGrant() = runBlocking {
        val id = UUID.randomUUID()
        outstanding.insert(
            OutstandingRequestEntity(
                requestId = id.toString(), targetPackage = "com.a", salt = ByteArray(16),
                issuedAt = 1_000, expiresAt = 10_000, consumed = 0,
            )
        )
        val repo = RoomOutstandingRequestRepository(db, outstanding, grants, db.dailyAllowanceDao())
        val allowance = DailyAllowance(
            "com.a", 60, LocalDate.ofEpochDay(100), LocalDate.ofEpochDay(106), ZoneId.of("UTC"), id, 5_000,
        )

        val results = (1..10).map { async { repo.consumeAndUpsertDailyAllowance(id, 5_000, allowance) } }.awaitAll()

        assertEquals(1, results.count { it })
        assertEquals(listOf("com.a"), db.dailyAllowanceDao().all().map { it.packageName })
        assertNull(grants.activeGrantForPackage("com.a", now = 0))
    }

    private fun rule(pkg: String, minutes: Int = 60, lastDay: Long = 106) = DailyAllowanceEntity(
        packageName = pkg, minutesPerDay = minutes, firstEpochDay = 100, lastEpochDay = lastDay,
        zoneId = "UTC", requestId = UUID.randomUUID().toString(), createdAt = 1,
    )

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

    @Test
    fun unlockGrantPruneExpiredDropsOnlyExpiredGrants() = runBlocking {
        grants.insert(UnlockGrantEntity(requestId = "a", targetPackage = "com.a", grantedAt = 1_000, expiresAt = 5_000))
        grants.insert(UnlockGrantEntity(requestId = "b", targetPackage = "com.b", grantedAt = 1_000, expiresAt = 2_000))

        assertEquals(1, grants.pruneExpired(now = 3_000))
        assertEquals(listOf("com.a"), grants.observeActiveAt(now = 0).first().map { it.targetPackage })
    }

    @Test
    fun outstandingPruneOldDropsExpiredAndOldConsumedRequests() = runBlocking {
        fun request(id: String, issuedAt: Long, expiresAt: Long, consumed: Int) = OutstandingRequestEntity(
            requestId = id, targetPackage = "com.example.app", salt = ByteArray(16),
            issuedAt = issuedAt, expiresAt = expiresAt, consumed = consumed,
        )
        outstanding.insert(request("expired", issuedAt = 1_000, expiresAt = 2_000, consumed = 0))
        outstanding.insert(request("oldConsumed", issuedAt = 1_000, expiresAt = 9_000, consumed = 1))
        outstanding.insert(request("open", issuedAt = 4_000, expiresAt = 9_000, consumed = 0))

        outstanding.pruneOld(now = 5_000, pruneBefore = 3_000)

        assertNull(outstanding.findById("expired"))
        assertNull(outstanding.findById("oldConsumed"))
        assertNotNull(outstanding.findById("open"))
    }
}
