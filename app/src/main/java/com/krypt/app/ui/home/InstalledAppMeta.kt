package com.krypt.app.ui.home

/** Lightweight identity record for an installed non-system app. */
data class InstalledAppMeta(
    val packageName: String,
    val displayName: String,
)
