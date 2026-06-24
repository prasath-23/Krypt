package com.krypt.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Krypt's SQLite persistence layer via Room.
 *
 * Schema version = 1. Every future schema change MUST bump the version
 * number and provide a `Migration` object. The generated schema JSON
 * under `app/schemas/com.krypt.app.data.KryptDatabase/1.json` is checked
 * into VCS and acts as the diff baseline.
 */
@Database(
    entities = [
        LockedAppEntity::class,
        GuardianPairingEntity::class,
        OutstandingRequestEntity::class,
        UnlockGrantEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KryptDatabase : RoomDatabase() {
    abstract fun lockedAppDao(): LockedAppDao
    abstract fun guardianPairingDao(): GuardianPairingDao
    abstract fun outstandingRequestDao(): OutstandingRequestDao
    abstract fun unlockGrantDao(): UnlockGrantDao

    companion object {
        const val DATABASE_NAME: String = "krypt.db"
    }
}
