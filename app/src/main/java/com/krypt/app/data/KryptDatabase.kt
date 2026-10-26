package com.krypt.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.krypt.app.data.daily.DailyAllowanceDao
import com.krypt.app.data.daily.DailyAllowanceEntity
import com.krypt.app.data.daily.MinuteUsageDao
import com.krypt.app.data.daily.MinuteUsageEntity

/**
 * Krypt's SQLite persistence layer via Room.
 *
 * Schema version = 3 (Amendment 3 added the every-day tables, see
 * [MIGRATION_1_2]; Amendment 6 counts their usage per minute, see
 * [MIGRATION_2_3]). Every future schema change MUST bump the version
 * number and provide a `Migration` object. The generated schema JSON
 * under `app/schemas/com.krypt.app.data.KryptDatabase/` is checked into
 * VCS and acts as the diff baseline.
 */
@Database(
    entities = [
        LockedAppEntity::class,
        GuardianPairingEntity::class,
        OutstandingRequestEntity::class,
        UnlockGrantEntity::class,
        DailyAllowanceEntity::class,
        MinuteUsageEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KryptDatabase : RoomDatabase() {
    abstract fun lockedAppDao(): LockedAppDao
    abstract fun guardianPairingDao(): GuardianPairingDao
    abstract fun outstandingRequestDao(): OutstandingRequestDao
    abstract fun unlockGrantDao(): UnlockGrantDao
    abstract fun dailyAllowanceDao(): DailyAllowanceDao
    abstract fun minuteUsageDao(): MinuteUsageDao

    companion object {
        const val DATABASE_NAME: String = "krypt.db"
    }
}
