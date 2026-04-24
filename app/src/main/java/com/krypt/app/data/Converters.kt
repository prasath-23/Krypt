package com.krypt.app.data

import androidx.room.TypeConverter

/**
 * Room @TypeConverters for enums. ByteArray goes through Room's native
 * BLOB mapping and needs no converter.
 */
class Converters {

    @TypeConverter fun lockStateToString(state: LockState): String = state.name
    @TypeConverter fun stringToLockState(value: String): LockState = LockState.valueOf(value)

    @TypeConverter fun lockSourceToString(src: LockSource): String = src.name
    @TypeConverter fun stringToLockSource(value: String): LockSource = LockSource.valueOf(value)

    @TypeConverter fun pairingRoleToString(role: PairingRole): String = role.name
    @TypeConverter fun stringToPairingRole(value: String): PairingRole = PairingRole.valueOf(value)
}
