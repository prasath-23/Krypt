package com.krypt.app.data.daily

import androidx.room.ColumnInfo
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
    /** Minutes in each period; named from when every period was a day. */
    val minutesPerDay: Int,
    val firstEpochDay: Long,
    /** Inclusive. */
    val lastEpochDay: Long,
    val zoneId: String,
    /** The request whose approval made this rule. */
    val requestId: String,
    val createdAt: Long,
    /** Minutes in a period; 1440, a day, for rules from before periods. */
    @ColumnInfo(defaultValue = "1440") val periodMinutes: Int = DailyAllowance.DAY_MINUTES,
)

/**
 * Time an app was used in one minute of the clock under an every-day rule.
 * [minute] is a [LocalMinutes] value in the rule's zone. Use saved per day,
 * before periods, sits in that day's first minute.
 */
@Entity(tableName = "minute_usage", primaryKeys = ["packageName", "minute"])
data class MinuteUsageEntity(
    val packageName: String,
    val minute: Long,
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
interface MinuteUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(usage: MinuteUsageEntity)

    @Query("SELECT * FROM minute_usage")
    suspend fun all(): List<MinuteUsageEntity>

    @Query("SELECT * FROM minute_usage WHERE minute >= :fromMinute")
    suspend fun from(fromMinute: Long): List<MinuteUsageEntity>

    @Query("SELECT usedMs FROM minute_usage WHERE packageName = :pkg AND minute = :minute")
    suspend fun usedMs(pkg: String, minute: Long): Long?

    /**
     * Usage from before [beforeMinute], except on days the app's rule still
     * covers: however far the date is moved, a minute's use is never
     * forgotten while it could count.
     */
    @Query(
        "DELETE FROM minute_usage WHERE minute < :beforeMinute AND NOT EXISTS " +
            "(SELECT 1 FROM daily_allowances a WHERE a.packageName = minute_usage.packageName " +
            "AND minute_usage.minute >= a.firstEpochDay * 1440)",
    )
    suspend fun pruneBefore(beforeMinute: Long): Int
}

internal fun DailyAllowanceEntity.toDomain() = DailyAllowance(
    packageName = packageName,
    minutes = minutesPerDay,
    firstDay = LocalDate.ofEpochDay(firstEpochDay),
    lastDay = LocalDate.ofEpochDay(lastEpochDay),
    zone = ZoneId.of(zoneId),
    requestId = UUID.fromString(requestId),
    createdAtMs = createdAt,
    periodMinutes = periodMinutes,
)

internal fun DailyAllowance.toEntity() = DailyAllowanceEntity(
    packageName = packageName,
    minutesPerDay = minutes,
    firstEpochDay = firstDay.toEpochDay(),
    lastEpochDay = lastDay.toEpochDay(),
    zoneId = zone.id,
    requestId = requestId.toString(),
    createdAt = createdAtMs,
    periodMinutes = periodMinutes,
)
