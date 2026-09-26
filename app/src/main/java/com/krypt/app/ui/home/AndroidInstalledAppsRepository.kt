package com.krypt.app.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [InstalledAppsRepository].
 *
 * Uses [PackageManager.getInstalledPackages] and filters out:
 *  - System apps (FLAG_SYSTEM without FLAG_UPDATED_SYSTEM_APP)
 *  - Krypt's own package
 *  - A curated denylist of OS launcher packages that would confuse the user
 *    if locked.
 *
 * The same filter decides which new installs are auto-locked
 * ([com.krypt.app.receiver.NewInstallLocker]), so an app the auto-locker
 * skips is also absent from the Home list (FR-036).
 *
 * Results are sorted using a locale-aware [Collator] so non-Latin app names
 * (Hindi, Chinese, Arabic) sort correctly.
 */
@Singleton
class AndroidInstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledAppsRepository {

    private val collator: Collator by lazy {
        Collator.getInstance().apply { strength = Collator.PRIMARY }
    }

    @Suppress("DEPRECATION") // The PackageInfoFlags overloads need API 33.
    override suspend fun allInstalled(): List<InstalledAppMeta> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledPackages(0)
                .mapNotNull { it.toUserAppMeta(pm) }
                .sortedWith(Comparator { a, b ->
                    collator.compare(a.displayName, b.displayName)
                })
        }

    @Suppress("DEPRECATION")
    override suspend fun find(packageName: String): InstalledAppMeta? =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            try {
                pm.getPackageInfo(packageName, 0).toUserAppMeta(pm)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

    @Suppress("DEPRECATION")
    override suspend fun kryptInstalledAtMs(): Long =
        withContext(Dispatchers.IO) {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }

    private fun PackageInfo.toUserAppMeta(pm: PackageManager): InstalledAppMeta? {
        val info = applicationInfo ?: return null
        if (!isUserApp(info, context.packageName)) return null
        return InstalledAppMeta(
            packageName = info.packageName,
            displayName = info.loadLabel(pm).toString(),
            firstInstallTimeMs = firstInstallTime,
        )
    }

    private fun isUserApp(info: ApplicationInfo, selfPackage: String): Boolean {
        if (info.packageName == selfPackage) return false
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem && !isUpdatedSystem) return false
        if (info.packageName in SYSTEM_PACKAGE_DENYLIST) return false
        return true
    }

    companion object {
        /** Packages that are system-adjacent but may lack FLAG_SYSTEM on some OEMs. */
        private val SYSTEM_PACKAGE_DENYLIST = setOf(
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.oneplus.launcher",
        )
    }
}
