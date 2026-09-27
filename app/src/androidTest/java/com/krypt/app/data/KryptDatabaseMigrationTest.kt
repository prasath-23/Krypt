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
    fun theAppOpensAMigratedDatabase() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            KryptDatabase::class.java,
            TEST_DB,
        ).addMigrations(MIGRATION_1_2).build()
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
