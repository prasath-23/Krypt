package com.krypt.app.data

import java.util.UUID

/**
 * Persistence seam for [OutstandingRequest] + the atomic
 * consume-and-grant transaction.
 *
 * Defined here (WP03) so [com.krypt.app.deeplink.ApprovalConsumer] can
 * take it as a constructor dependency. Concrete Room-backed implementation
 * lands in WP06. A pure-memory `FakeOutstandingRequestRepository` lives in
 * `src/test/` for [com.krypt.app.deeplink.ApprovalConsumer]'s unit tests.
 */
interface OutstandingRequestRepository {

    suspend fun insert(request: OutstandingRequest)

    suspend fun findById(id: UUID): OutstandingRequest?

    /**
     * Atomic transaction:
     *
     *   IF [requestId] exists AND NOT consumed AND expiresAtMs > [nowMs]
     *   THEN set consumed = true AND insert [grant], returning its row id.
     *   ELSE do nothing and return null.
     *
     * Implementations MUST perform both steps inside the same database
     * transaction (room-ktx `withTransaction { ... }`). The match condition
     * MUST be expressed as a single SQL `UPDATE ... WHERE ... AND consumed=0`
     * (not a read-then-write), so that two racing approval consumptions
     * yield exactly one success.
     */
    suspend fun consumeAndInsertGrant(
        requestId: UUID,
        nowMs: Long,
        grant: UnlockGrant,
    ): Long?
}

/**
 * Separate persistence seam for [UnlockGrant] reads. Writes go through
 * [OutstandingRequestRepository.consumeAndInsertGrant]; this interface
 * exists for the read path (LockerSessionStore / UI).
 */
interface UnlockGrantRepository {

    suspend fun activeGrantFor(pkg: String, nowMs: Long): UnlockGrant?

    suspend fun deleteExpired(nowMs: Long): Int
}
