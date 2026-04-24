package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.crypto.SecureRandomSource
import com.krypt.app.data.OutstandingRequest
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.UnlockGrant
import java.util.UUID

/** Deterministic [Clock] for tests. */
internal class FixedClock(var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

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
    private var nextGrantId: Long = 1L

    override suspend fun insert(request: OutstandingRequest) {
        requests[request.requestId] = request
    }

    override suspend fun findById(id: UUID): OutstandingRequest? = requests[id]

    @Synchronized
    override suspend fun consumeAndInsertGrant(
        requestId: UUID,
        nowMs: Long,
        grant: UnlockGrant,
    ): Long? {
        val existing = requests[requestId] ?: return null
        if (existing.consumed) return null
        if (nowMs > existing.expiresAtMs) return null
        requests[requestId] = existing.copy(consumed = true)
        val id = nextGrantId++
        grants += grant.copy(id = id)
        return id
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
