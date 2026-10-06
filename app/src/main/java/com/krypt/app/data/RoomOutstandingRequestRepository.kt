package com.krypt.app.data

import androidx.room.withTransaction
import com.krypt.app.data.daily.DailyAllowance
import com.krypt.app.data.daily.DailyAllowanceDao
import com.krypt.app.data.daily.toEntity
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
    private val dailyDao: DailyAllowanceDao,
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

    override suspend fun consumeAndUpsertDailyAllowance(
        requestId: UUID,
        nowMs: Long,
        allowance: DailyAllowance,
    ): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val affected = requestDao.markConsumedIfOpen(requestId.toString(), nowMs)
            if (affected == 0) return@withTransaction false
            dailyDao.upsert(allowance.toEntity())
            true
        }
    }

    override suspend fun pruneStale(nowMs: Long) {
        requestDao.pruneOld(now = nowMs, pruneBefore = nowMs - CONSUMED_RETENTION_MS)
    }

    private fun OutstandingRequestEntity.toDomain(): OutstandingRequest = OutstandingRequest(
        requestId = UUID.fromString(requestId),
        targetPackage = targetPackage,
        salt = salt,
        issuedAtMs = issuedAt,
        expiresAtMs = expiresAt,
        consumed = consumed != 0,
    )

    private companion object {
        /** Consumed rows are kept for a day as a record of recent unlocks. */
        const val CONSUMED_RETENTION_MS = 24 * 60 * 60 * 1000L
    }
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
