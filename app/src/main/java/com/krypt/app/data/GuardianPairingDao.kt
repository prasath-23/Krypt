package com.krypt.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardianPairingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pairing: GuardianPairingEntity)

    @Query("SELECT * FROM guardian_pairing WHERE id = 1")
    suspend fun getPairing(): GuardianPairingEntity?

    @Query("SELECT * FROM guardian_pairing WHERE id = 1")
    fun observePairing(): Flow<GuardianPairingEntity?>

    @Query("DELETE FROM guardian_pairing")
    suspend fun clear()
}
