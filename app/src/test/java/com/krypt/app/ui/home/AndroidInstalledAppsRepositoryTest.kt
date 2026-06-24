package com.krypt.app.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AndroidInstalledAppsRepository].
 *
 * Uses MockK to simulate PackageManager responses without a device.
 */
class AndroidInstalledAppsRepositoryTest {

    private val ctx = mockk<Context>(relaxed = true)
    private val pm = mockk<PackageManager>(relaxed = true)
    private lateinit var repo: AndroidInstalledAppsRepository

    @Before
    fun setUp() {
        every { ctx.packageManager } returns pm
        every { ctx.packageName } returns "com.krypt.app"
        repo = AndroidInstalledAppsRepository(ctx)
    }

    private fun makeAppInfo(pkg: String, flags: Int = 0, label: String = pkg): ApplicationInfo {
        val appInfo = mockk<ApplicationInfo>(relaxed = true)
        appInfo.packageName = pkg
        appInfo.flags = flags
        every { appInfo.loadLabel(pm) } returns label
        return appInfo
    }

    @Test
    fun allInstalled_excludesSystemApps() = runTest {
        val system = makeAppInfo("com.android.settings", ApplicationInfo.FLAG_SYSTEM)
        val user = makeAppInfo("com.example.user", 0, "UserApp")
        every { pm.getInstalledApplications(0) } returns listOf(system, user)

        val result = repo.allInstalled()

        assertEquals(1, result.size)
        assertEquals("com.example.user", result[0].packageName)
    }

    @Test
    fun allInstalled_excludesKryptPackage() = runTest {
        val krypt = makeAppInfo("com.krypt.app", 0, "Krypt")
        val other = makeAppInfo("com.example.other", 0, "Other")
        every { pm.getInstalledApplications(0) } returns listOf(krypt, other)

        val result = repo.allInstalled()

        assertTrue(result.none { it.packageName == "com.krypt.app" })
    }

    @Test
    fun allInstalled_excludesDenylistedLaunchers() = runTest {
        val launcher = makeAppInfo("com.miui.home", 0, "Home")
        val userApp = makeAppInfo("com.example.notes", 0, "Notes")
        every { pm.getInstalledApplications(0) } returns listOf(launcher, userApp)

        val result = repo.allInstalled()

        assertFalse(result.any { it.packageName == "com.miui.home" })
        assertTrue(result.any { it.packageName == "com.example.notes" })
    }

    @Test
    fun allInstalled_sortedAlphabetically() = runTest {
        val apps = listOf(
            makeAppInfo("com.z", 0, "Zebra"),
            makeAppInfo("com.a", 0, "Apple"),
            makeAppInfo("com.m", 0, "Mango"),
        )
        every { pm.getInstalledApplications(0) } returns apps

        val result = repo.allInstalled()

        assertEquals(listOf("Apple", "Mango", "Zebra"), result.map { it.displayName })
    }

    @Test
    fun allInstalled_updatedSystemAppIncluded() = runTest {
        val updatedSystem = makeAppInfo(
            "com.google.android.gms",
            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
            "Google Play Services",
        )
        every { pm.getInstalledApplications(0) } returns listOf(updatedSystem)

        val result = repo.allInstalled()

        assertEquals(1, result.size)
        assertEquals("com.google.android.gms", result[0].packageName)
    }

    @Test
    fun allInstalled_emptyList_whenNoUserApps() = runTest {
        every { pm.getInstalledApplications(0) } returns emptyList()

        val result = repo.allInstalled()

        assertTrue(result.isEmpty())
    }
}
