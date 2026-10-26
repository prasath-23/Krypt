package com.krypt.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema 1 -> 2 (Amendment 3): every-day allowances. Only adds tables, so
 * nothing already stored - above all the locked apps - is touched.
 * The SQL is Room's own, from app/schemas/.../2.json.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_allowances` (`packageName` TEXT NOT NULL, " +
                "`minutesPerDay` INTEGER NOT NULL, `firstEpochDay` INTEGER NOT NULL, " +
                "`lastEpochDay` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `requestId` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`packageName`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_usage` (`packageName` TEXT NOT NULL, " +
                "`epochDay` INTEGER NOT NULL, `usedMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`packageName`, `epochDay`))"
        )
    }
}

/**
 * Schema 2 -> 3 (Amendment 6): every-day rules count in periods, so time
 * used is kept per minute of the clock, which any period can add up.
 *
 * - Rules already saved get `periodMinutes` 1440: a day, as before.
 * - `minute_usage` replaces `daily_usage`. Each day's time moves to that
 *   day's first minute, so it still counts in full against the day.
 *
 * The CREATE is Room's own, from app/schemas/.../3.json.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `daily_allowances` ADD COLUMN `periodMinutes` INTEGER NOT NULL DEFAULT 1440")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `minute_usage` (`packageName` TEXT NOT NULL, " +
                "`minute` INTEGER NOT NULL, `usedMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`packageName`, `minute`))"
        )
        db.execSQL(
            "INSERT INTO `minute_usage` (`packageName`, `minute`, `usedMs`) " +
                "SELECT `packageName`, `epochDay` * 1440, `usedMs` FROM `daily_usage`"
        )
        db.execSQL("DROP TABLE `daily_usage`")
    }
}
