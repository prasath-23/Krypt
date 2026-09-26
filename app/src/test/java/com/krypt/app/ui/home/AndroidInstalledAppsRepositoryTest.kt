package com.krypt.app.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun makePackage(
        pkg: String,
        flags: Int = 0,
        label: String = pkg,
        installedAt: Long = 0L,
    ): PackageInfo {
        val appInfo = mockk<ApplicationInfo>(relaxed = true)
        appInfo.packageName = pkg
        appInfo.flags = flags
        every { appInfo.loadLabel(pm) } returns label
        val info = mockk<PackageInfo>(relaxed = true)
        info.packageName = pkg
        info.applicationInfo = appInfo
        info.firstInstallTime = installedAt
        return info
    }

    private fun installed(vararg packages: PackageInfo) {
        every { pm.getInstalledPackages(0) } returns packages.toList()
        packages.forEach { every { pm.getPackageInfo(it.packageName, 0) } returns it }
    }

    @Test
    fun allInstalled_excludesSystemApps() = runTest {
        installed(
            makePackage("com.android.settings", ApplicationInfo.FLAG_SYSTEM),
            makePackage("com.example.user", 0, "UserApp"),
        )

        val result = repo.allInstalled()

        assertEquals(1, result.size)
        assertEquals("com.example.user", result[0].packageName)
    }

    @Test
    fun allInstalled_excludesKryptPackage() = runTest {
        installed(makePackage("com.krypt.app", 0, "Krypt"), makePackage("com.example.other", 0, "Other"))

        val result = repo.allInstalled()

        assertTrue(result.none { it.packageName == "com.krypt.app" })
    }

    @Test
    fun allInstalled_excludesDenylistedLaunchers() = runTest {
        installed(makePackage("com.miui.home", 0, "Home"), makePackage("com.example.notes", 0, "Notes"))

        val result = repo.allInstalled()

        assertFalse(result.any { it.packageName == "com.miui.home" })
        assertTrue(result.any { it.packageName == "com.example.notes" })
    }

    @Test
    fun allInstalled_sortedAlphabetically() = runTest {
        installed(
            makePackage("com.z", 0, "Zebra"),
            makePackage("com.a", 0, "Apple"),
            makePackage("com.m", 0, "Mango"),
        )

        val result = repo.allInstalled()

        assertEquals(listOf("Apple", "Mango", "Zebra"), result.map { it.displayName })
    }

    @Test
    fun allInstalled_updatedSystemAppIncluded() = runTest {
        installed(
            makePackage(
                "com.google.android.gms",
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                "Google Play Services",
            )
        )

        val result = repo.allInstalled()

        assertEquals(1, result.size)
        assertEquals("com.google.android.gms", result[0].packageName)
    }

    @Test
    fun allInstalled_emptyList_whenNoUserApps() = runTest {
        installed()

        val result = repo.allInstalled()

        assertTrue(result.isEmpty())
    }

    @Test
    fun allInstalled_carriesTheInstallTime() = runTest {
        installed(makePackage("com.example.app", 0, "App", installedAt = 1_234L))

        assertEquals(1_234L, repo.allInstalled().single().firstInstallTimeMs)
    }

    @Test
    fun find_returnsAUserApp() = runTest {
        installed(makePackage("com.example.app", 0, "App", installedAt = 99L))

        assertEquals(InstalledAppMeta("com.example.app", "App", 99L), repo.find("com.example.app"))
    }

    @Test
    fun find_appliesTheSameFilterAsTheHomeList() = runTest {
        installed(
            makePackage("com.android.settings", ApplicationInfo.FLAG_SYSTEM),
            makePackage("com.miui.home"),
            makePackage("com.krypt.app"),
        )

        assertNull(repo.find("com.android.settings"))
        assertNull(repo.find("com.miui.home"))
        assertNull(repo.find("com.krypt.app"))
    }

    @Test
    fun find_returnsNullForAnUninstalledPackage() = runTest {
        every { pm.getPackageInfo("com.gone", 0) } throws PackageManager.NameNotFoundException()

        assertNull(repo.find("com.gone"))
    }

    @Test
    fun kryptInstalledAt_isKryptsOwnInstallTime() = runTest {
        installed(makePackage("com.krypt.app", installedAt = 5_000L))

        assertEquals(5_000L, repo.kryptInstalledAtMs())
    }
}
