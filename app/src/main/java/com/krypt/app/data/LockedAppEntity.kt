package com.krypt.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locked_apps")
data class LockedAppEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val iconUri: String? = null,
    val lockState: LockState,
    val lockSource: LockSource,
    val createdAt: Long,
    val updatedAt: Long,
)
