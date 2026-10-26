package com.krypt.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema 1 -> 2 must keep everything already stored - above all the locked
 * apps: losing them would unlock every app - and add the every-day tables.
 * Schema 2 -> 3 keeps them too, and keeps every-day rules and their use
 * counting as they did: a day, with each day's use in its first minute.
 */
@RunWith(AndroidJUnit4::class)
class KryptDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KryptDatabase::class.java)

    @Test
    fun migrating1To2_keepsLockedAppsAndGrants_andAddsTheDailyTables() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO locked_apps (packageName, displayName, iconUri, lockState, lockSource, createdAt, updatedAt) " +
                    "VALUES ('com.example.a', 'A', NULL, 'LOCKED', 'MANUAL', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO unlock_grants (requestId, targetPackage, grantedAt, expiresAt) " +
                    "VALUES ('req', 'com.example.a', 1, 2)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT lockState FROM locked_apps WHERE packageName = 'com.example.a'").use { c ->
                assertTrue("locked app kept", c.moveToFirst())
                assertEquals("LOCKED", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM unlock_grants").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.execSQL("INSERT INTO daily_allowances VALUES ('com.example.a', 60, 20000, 20001, 'UTC', 'req', 1)")
            db.execSQL("INSERT INTO daily_usage VALUES ('com.example.a', 20000, 1000)")
        }
    }

    @Test
    fun migrating2To3_keepsRulesAsADay_andMovesEachDaysUseToItsFirstMinute() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO locked_apps (packageName, displayName, iconUri, lockState, lockSource, createdAt, updatedAt) " +
                    "VALUES ('com.example.a', 'A', NULL, 'LOCKED', 'MANUAL', 1, 1)"
            )
            db.execSQL("INSERT INTO daily_allowances VALUES ('com.example.a', 60, 20000, 20006, 'UTC', 'req', 1)")
            db.execSQL("INSERT INTO daily_usage VALUES ('com.example.a', 20000, 1000)")
            db.execSQL("INSERT INTO daily_usage VALUES ('com.example.a', 20001, 2000)")
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT lockState FROM locked_apps WHERE packageName = 'com.example.a'").use { c ->
                assertTrue("locked app kept", c.moveToFirst())
                assertEquals("LOCKED", c.getString(0))
            }
            db.query("SELECT minutesPerDay, periodMinutes FROM daily_allowances").use { c ->
                assertTrue("rule kept", c.moveToFirst())
                assertEquals(60, c.getInt(0))
                assertEquals("a day, as before", 1440, c.getInt(1))
            }
            db.query("SELECT minute, usedMs FROM minute_usage ORDER BY minute").use { c ->
                val rows = buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
                assertEquals(listOf(20000L * 1440 to 1000L, 20001L * 1440 to 2000L), rows)
            }
        }
    }

    @Test
    fun theAppOpensAMigratedDatabase() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            KryptDatabase::class.java,
            TEST_DB,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            runBlocking { assertEquals(emptyList<Any>(), db.dailyAllowanceDao().all()) }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "krypt-migration-test.db"
    }
}
