package com.krypt.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LockedAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: LockedAppEntity)

    @Query("SELECT * FROM locked_apps WHERE packageName = :pkg")
    suspend fun findByPackage(pkg: String): LockedAppEntity?

    @Query("SELECT * FROM locked_apps ORDER BY displayName")
    fun observeAll(): Flow<List<LockedAppEntity>>

    @Query("SELECT COUNT(*) FROM locked_apps WHERE lockState = 'LOCKED'")
    fun observeLockedCount(): Flow<Int>

    @Query("UPDATE locked_apps SET lockState = :state, updatedAt = :ts WHERE packageName = :pkg")
    suspend fun updateLockState(pkg: String, state: LockState, ts: Long)

    @Delete
    suspend fun delete(app: LockedAppEntity)
}
