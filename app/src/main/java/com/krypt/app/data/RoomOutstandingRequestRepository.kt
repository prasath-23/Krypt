package com.krypt.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed impl of [OutstandingRequestRepository] (interface from WP03).
 * Implements the atomic consume-and-grant transaction via `withTransaction`.
 */
@Singleton
class RoomOutstandingRequestRepository @Inject constructor(
    private val db: KryptDatabase,
    private val requestDao: OutstandingRequestDao,
    private val grantDao: UnlockGrantDao,
) : OutstandingRequestRepository {

    override suspend fun insert(request: OutstandingRequest) {
        requestDao.insert(
            OutstandingRequestEntity(
                requestId = request.requestId.toString(),
                targetPackage = request.targetPackage,
                salt = request.salt,
                issuedAt = request.issuedAtMs,
                expiresAt = request.expiresAtMs,
                consumed = if (request.consumed) 1 else 0,
            )
        )
    }

    override suspend fun findById(id: UUID): OutstandingRequest? =
        requestDao.findById(id.toString())?.toDomain()

    override suspend fun consumeAndInsertGrant(
        requestId: UUID,
        nowMs: Long,
        grant: UnlockGrant,
    ): Long? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val affected = requestDao.markConsumedIfOpen(requestId.toString(), nowMs)
            if (affected == 0) return@withTransaction null
            grantDao.insert(
                UnlockGrantEntity(
                    requestId = grant.requestId.toString(),
                    targetPackage = grant.targetPackage,
                    grantedAt = grant.grantedAtMs,
                    expiresAt = grant.expiresAtMs,
                )
            )
        }
    }

    private fun OutstandingRequestEntity.toDomain(): OutstandingRequest = OutstandingRequest(
        requestId = UUID.fromString(requestId),
        targetPackage = targetPackage,
        salt = salt,
        issuedAtMs = issuedAt,
        expiresAtMs = expiresAt,
        consumed = consumed != 0,
    )
}

/** Room-backed impl of [UnlockGrantRepository] (interface from WP03). */
@Singleton
class RoomUnlockGrantRepository @Inject constructor(
    private val grantDao: UnlockGrantDao,
) : UnlockGrantRepository {

    override suspend fun activeGrantFor(pkg: String, nowMs: Long): UnlockGrant? =
        grantDao.activeGrantForPackage(pkg, nowMs)?.toDomain()

    override suspend fun deleteExpired(nowMs: Long): Int =
        grantDao.pruneExpired(nowMs)

    private fun UnlockGrantEntity.toDomain(): UnlockGrant = UnlockGrant(
        id = id,
        requestId = UUID.fromString(requestId),
        targetPackage = targetPackage,
        grantedAtMs = grantedAt,
        expiresAtMs = expiresAt,
    )
}
