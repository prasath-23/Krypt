package com.krypt.app.ui.home

/** Lightweight identity record for an installed non-system app. */
data class InstalledAppMeta(
    val packageName: String,
    val displayName: String,
    /** `PackageInfo.firstInstallTime`; reset by a reinstall, unchanged by updates. */
    val firstInstallTimeMs: Long = 0L,
)
