package com.krypt.app.ui.home

/** Data source for all installed non-system apps on the device. */
interface InstalledAppsRepository {
    /** Returns all installed non-system apps, sorted alphabetically by display name. */
    suspend fun allInstalled(): List<InstalledAppMeta>

    /**
     * The installed non-system app [packageName], or null when it is not
     * installed or is filtered out (system app, launcher, Krypt itself).
     */
    suspend fun find(packageName: String): InstalledAppMeta?

    /** When Krypt itself was first installed; apps installed later are auto-locked. */
    suspend fun kryptInstalledAtMs(): Long
}
