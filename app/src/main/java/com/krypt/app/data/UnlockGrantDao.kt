package com.krypt.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UnlockGrantDao {

    @Insert
    suspend fun insert(grant: UnlockGrantEntity): Long

    @Query(
        "SELECT * FROM unlock_grants " +
        "WHERE targetPackage = :pkg AND expiresAt > :now " +
        "ORDER BY expiresAt DESC LIMIT 1"
    )
    suspend fun activeGrantForPackage(pkg: String, now: Long): UnlockGrantEntity?

    @Query("SELECT * FROM unlock_grants WHERE expiresAt > :now")
    fun observeActiveAt(now: Long): Flow<List<UnlockGrantEntity>>

    @Query("DELETE FROM unlock_grants WHERE expiresAt < :now")
    suspend fun pruneExpired(now: Long): Int
}
