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
