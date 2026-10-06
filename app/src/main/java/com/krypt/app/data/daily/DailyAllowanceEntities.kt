package com.krypt.app.data.daily

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** An every-day rule; one per app. Days are epoch days in [zoneId]. */
@Entity(tableName = "daily_allowances")
data class DailyAllowanceEntity(
    @PrimaryKey val packageName: String,
    val minutesPerDay: Int,
    val firstEpochDay: Long,
    /** Inclusive. */
    val lastEpochDay: Long,
    val zoneId: String,
    /** The request whose approval made this rule. */
    val requestId: String,
    val createdAt: Long,
)

/** Time an app was used on one day under an every-day rule. */
@Entity(tableName = "daily_usage", primaryKeys = ["packageName", "epochDay"])
data class DailyUsageEntity(
    val packageName: String,
    val epochDay: Long,
    val usedMs: Long,
)

@Dao
interface DailyAllowanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: DailyAllowanceEntity)

    @Query("SELECT * FROM daily_allowances")
    suspend fun all(): List<DailyAllowanceEntity>

    @Query("DELETE FROM daily_allowances WHERE packageName = :pkg")
    suspend fun delete(pkg: String): Int

    @Query("DELETE FROM daily_allowances WHERE lastEpochDay < :beforeEpochDay")
    suspend fun pruneEndedBefore(beforeEpochDay: Long): Int
}

@Dao
interface DailyUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(usage: DailyUsageEntity)

    @Query("SELECT * FROM daily_usage")
    suspend fun all(): List<DailyUsageEntity>

    @Query("SELECT usedMs FROM daily_usage WHERE packageName = :pkg AND epochDay = :epochDay")
    suspend fun usedMs(pkg: String, epochDay: Long): Long?

    /**
     * Usage from before [beforeEpochDay], except on days the app's rule still
     * covers: however far the date is moved, a day's use is never forgotten
     * while it could count.
     */
    @Query(
        "DELETE FROM daily_usage WHERE epochDay < :beforeEpochDay AND NOT EXISTS " +
            "(SELECT 1 FROM daily_allowances a WHERE a.packageName = daily_usage.packageName " +
            "AND daily_usage.epochDay >= a.firstEpochDay)",
    )
    suspend fun pruneBefore(beforeEpochDay: Long): Int
}

internal fun DailyAllowanceEntity.toDomain() = DailyAllowance(
    packageName = packageName,
    minutesPerDay = minutesPerDay,
    firstDay = LocalDate.ofEpochDay(firstEpochDay),
    lastDay = LocalDate.ofEpochDay(lastEpochDay),
    zone = ZoneId.of(zoneId),
    requestId = UUID.fromString(requestId),
    createdAtMs = createdAt,
)

internal fun DailyAllowance.toEntity() = DailyAllowanceEntity(
    packageName = packageName,
    minutesPerDay = minutesPerDay,
    firstEpochDay = firstDay.toEpochDay(),
    lastEpochDay = lastDay.toEpochDay(),
    zoneId = zone.id,
    requestId = requestId.toString(),
    createdAt = createdAtMs,
)
