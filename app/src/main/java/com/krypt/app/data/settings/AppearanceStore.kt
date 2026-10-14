package com.krypt.app.data.settings

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** How Krypt's screens look: like the phone, or always light, or always dark. */
enum class Appearance { SYSTEM, LIGHT, DARK }

/**
 * The Appearance setting (docs/design-system/components/ThemeSetting).
 *
 * Kept in plain SharedPreferences, not DataStore: it is read synchronously
 * at process start, before any window exists, so even a screen's first
 * frame (its window background) is drawn in the chosen theme. It is not
 * sensitive.
 *
 * The choice is applied twice, see [NightMode]: in this process, as
 * AppCompat's default night mode, which every Krypt activity (all
 * AppCompat, the lock screen included) applies to itself; and on Android
 * 12+ as the system's per-app night mode too, which the system uses for the
 * windows it draws for Krypt, such as starting windows. The first alone is
 * not enough on 12+, and the second alone is not either: the system applies
 * the per-app mode to each activity, while AppCompat's "follow system"
 * reads the application's configuration, which keeps the phone's mode.
 */
@Singleton
class AppearanceStore internal constructor(
    private val prefs: SharedPreferences,
    private val nightMode: NightMode,
) {

    /** Where a choice is put into effect. */
    interface NightMode {
        /** AppCompat's default night mode for this process. Main thread. */
        fun applyInProcess(appearance: Appearance)

        /** The system's per-app night mode (Android 12+; a no-op before). Any thread. */
        fun applyToSystem(appearance: Appearance)
    }

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        nightMode = PlatformNightMode(context),
    )

    private val _appearance = MutableStateFlow(read())

    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    /** Puts the stored choice into effect for this process. Call on the main thread at process start. */
    fun applyStored() = nightMode.applyInProcess(_appearance.value)

    /**
     * Re-syncs the system's copy of the stored choice. The system keeps it
     * across restarts, so at process start this can run off the main thread.
     */
    fun syncSystem() = nightMode.applyToSystem(_appearance.value)

    /** Saves [value] and applies it at once; open Krypt screens redraw in the new theme. */
    fun set(value: Appearance) {
        if (value == _appearance.value) return
        prefs.edit().putString(KEY, value.name).apply()
        _appearance.value = value
        nightMode.applyToSystem(value)
        nightMode.applyInProcess(value)
    }

    private fun read(): Appearance {
        val stored = prefs.getString(KEY, null) ?: return Appearance.SYSTEM
        return Appearance.entries.firstOrNull { it.name == stored } ?: Appearance.SYSTEM
    }

    private class PlatformNightMode(private val context: Context) : NightMode {

        override fun applyInProcess(appearance: Appearance) {
            AppCompatDelegate.setDefaultNightMode(
                when (appearance) {
                    Appearance.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    Appearance.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    Appearance.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                },
            )
        }

        override fun applyToSystem(appearance: Appearance) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            // MODE_NIGHT_AUTO clears the app's own night mode, so it follows the phone.
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                when (appearance) {
                    Appearance.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    Appearance.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    Appearance.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "krypt_appearance"
        const val KEY = "appearance"
    }
}
