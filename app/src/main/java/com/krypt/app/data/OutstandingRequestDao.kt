package com.krypt.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutstandingRequestDao {

    @Insert
    suspend fun insert(req: OutstandingRequestEntity)

    @Query("SELECT * FROM outstanding_requests WHERE requestId = :id")
    suspend fun findById(id: String): OutstandingRequestEntity?

    @Query("SELECT * FROM outstanding_requests WHERE consumed = 0 AND expiresAt > :now")
    fun observeOpenAt(now: Long): Flow<List<OutstandingRequestEntity>>

    /**
     * Atomic UPDATE: flip consumed=0 -> consumed=1 only if still open+unexpired.
     * Returns 1 on successful flip, 0 otherwise — the sole race-safe gate
     * against approval replay (FR-015).
     */
    @Query(
        "UPDATE outstanding_requests SET consumed = 1 " +
        "WHERE requestId = :id AND consumed = 0 AND expiresAt > :now"
    )
    suspend fun markConsumedIfOpen(id: String, now: Long): Int

    @Query(
        "DELETE FROM outstanding_requests " +
        "WHERE expiresAt < :now OR (consumed = 1 AND issuedAt < :pruneBefore)"
    )
    suspend fun pruneOld(now: Long, pruneBefore: Long)
}
