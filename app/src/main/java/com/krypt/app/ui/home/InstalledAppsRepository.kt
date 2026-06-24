package com.krypt.app.ui.home

/** Data source for all installed non-system apps on the device. */
interface InstalledAppsRepository {
    /** Returns all installed non-system apps, sorted alphabetically by display name. */
    suspend fun allInstalled(): List<InstalledAppMeta>
}
