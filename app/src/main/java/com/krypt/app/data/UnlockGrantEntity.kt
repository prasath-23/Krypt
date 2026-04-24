package com.krypt.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "unlock_grants")
data class UnlockGrantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val requestId: String,
    val targetPackage: String,
    val grantedAt: Long,
    val expiresAt: Long,
)
