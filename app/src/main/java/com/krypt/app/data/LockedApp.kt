package com.krypt.app.data

/** Domain-layer projection of [LockedAppEntity]. */
data class LockedApp(
    val packageName: String,
    val displayName: String,
    val iconUri: String? = null,
    val lockState: LockState,
    val lockSource: LockSource,
    val createdAt: Long,
    val updatedAt: Long,
)

internal fun LockedAppEntity.toDomain(): LockedApp = LockedApp(
    packageName, displayName, iconUri, lockState, lockSource, createdAt, updatedAt,
)

internal fun LockedApp.toEntity(): LockedAppEntity = LockedAppEntity(
    packageName, displayName, iconUri, lockState, lockSource, createdAt, updatedAt,
)
