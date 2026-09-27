package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.common.TrustedDayClock
import com.krypt.app.crypto.SecureRandomSource
import com.krypt.app.data.OutstandingRequest
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.UnlockGrant
import com.krypt.app.data.daily.DailyAllowance
import com.krypt.app.security.FakeSharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Deterministic [Clock] for tests. */
internal class FixedClock(var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

/** A [TrustedDayClock] on [clock], with automatic date & time on unless [autoTime] says otherwise. */
internal fun testDayClock(clock: Clock, autoTime: () -> Boolean = { true }) =
    TrustedDayClock(FakeSharedPreferences(), clock) { autoTime() }

/**
 * Non-cryptographic, deterministic [SecureRandomSource] for tests. NEVER
 * use in production - outputs are a trivially-derivable permutation of the
 * seed bytes.
 */
internal class DeterministicRandom(seed: ByteArray) : SecureRandomSource {
    private val seedBytes = seed.copyOf()
    private var counter = 0
    override fun nextBytes(size: Int): ByteArray =
        ByteArray(size) { i -> (seedBytes[(counter++) % seedBytes.size].toInt() + i).toByte() }
}

/**
 * In-memory [OutstandingRequestRepository] that honours the atomic
 * consume-and-grant contract. Shared across Amendment 1 test files.
 */
internal class FakeOutstandingRequestRepository : OutstandingRequestRepository {
    val requests: MutableMap<UUID, OutstandingRequest> = mutableMapOf()
    val grants: MutableList<UnlockGrant> = mutableListOf()
    val dailyRules: MutableMap<String, DailyAllowance> = mutableMapOf()
    private var nextGrantId: Long = 1L
    private val mutex = Mutex()

    override suspend fun insert(request: OutstandingRequest) {
        requests[request.requestId] = request
    }

    override suspend fun findById(id: UUID): OutstandingRequest? = requests[id]

    override suspend fun consumeAndInsertGrant(
        requestId: UUID,
        nowMs: Long,
        grant: UnlockGrant,
    ): Long? = mutex.withLock {
        val existing = requests[requestId] ?: return@withLock null
        if (existing.consumed) return@withLock null
        if (nowMs > existing.expiresAtMs) return@withLock null
        requests[requestId] = existing.copy(consumed = true)
        val id = nextGrantId++
        grants += grant.copy(id = id)
        id
    }

    override suspend fun consumeAndUpsertDailyAllowance(
        requestId: UUID,
        nowMs: Long,
        allowance: DailyAllowance,
    ): Boolean = mutex.withLock {
        val existing = requests[requestId] ?: return@withLock false
        if (existing.consumed) return@withLock false
        if (nowMs > existing.expiresAtMs) return@withLock false
        requests[requestId] = existing.copy(consumed = true)
        dailyRules[allowance.packageName] = allowance
        true
    }

    var pruneCalls: Int = 0
        private set

    override suspend fun pruneStale(nowMs: Long) {
        pruneCalls++
        requests.values.removeAll { it.expiresAtMs < nowMs }
    }
}

/**
 * Convert an [UnlockRequest] into a storable [OutstandingRequest] using
 * the given `nowMs` as the issue time. Keeps Amendment 1 test files terse.
 */
internal fun UnlockRequest.toOutstanding(nowMs: Long): OutstandingRequest =
    OutstandingRequest(
        requestId = requestId,
        targetPackage = targetPackage,
        salt = salt,
        issuedAtMs = nowMs,
        expiresAtMs = nowMs + ttlSeconds * 1000,
        consumed = false,
    )
