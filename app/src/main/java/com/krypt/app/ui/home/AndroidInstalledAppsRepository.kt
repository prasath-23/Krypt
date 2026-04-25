package com.krypt.app.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [InstalledAppsRepository].
 *
 * Uses [android.content.pm.PackageManager.getInstalledApplications] and
 * filters out:
 *  - System apps (FLAG_SYSTEM and FLAG_UPDATED_SYSTEM_APP)
 *  - Krypt's own package
 *  - A curated allowlist of OS launcher / IME / setup packages that would
 *    confuse the user if locked (same filter logic as [PackageReceiver]).
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

    override suspend fun allInstalled(): List<InstalledAppMeta> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .filter { info -> isUserApp(info, context.packageName) }
                .map { info ->
                    InstalledAppMeta(
                        packageName = info.packageName,
                        displayName = info.loadLabel(pm).toString(),
                    )
                }
                .sortedWith(Comparator { a, b ->
                    collator.compare(a.displayName, b.displayName)
                })
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
