package com.krypt.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scalar settings backed by Preferences DataStore.
 *
 * (Proto DataStore was the plan's first choice; we use Preferences DataStore
 * as the documented fallback — see plan risks section. It is simpler, has
 * zero codegen, and fully covers the four scalar fields we need.)
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settings: Flow<KryptSettings> = context.dataStore.data.map { prefs ->
        KryptSettings(
            kdfIterations = prefs[Keys.KDF_ITER] ?: DEFAULT_KDF_ITER,
            defaultGrantMinutes = prefs[Keys.DEFAULT_GRANT_MIN] ?: DEFAULT_GRANT_MIN,
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            lastGuardianPairAtMs = prefs[Keys.LAST_PAIR_AT] ?: 0L,
        )
    }

    suspend fun setKdfIterations(iterations: Int) {
        context.dataStore.edit { it[Keys.KDF_ITER] = iterations }
    }

    suspend fun setDefaultGrantMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.DEFAULT_GRANT_MIN] = minutes }
    }

    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = value }
    }

    suspend fun setLastGuardianPairAt(ms: Long) {
        context.dataStore.edit { it[Keys.LAST_PAIR_AT] = ms }
    }

    private object Keys {
        val KDF_ITER = intPreferencesKey("kdf_iterations")
        val DEFAULT_GRANT_MIN = intPreferencesKey("default_grant_minutes")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val LAST_PAIR_AT = longPreferencesKey("last_guardian_pair_at")
    }

    companion object {
        const val DEFAULT_KDF_ITER = 300_000
        const val DEFAULT_GRANT_MIN = 15
    }
}

data class KryptSettings(
    val kdfIterations: Int,
    val defaultGrantMinutes: Int,
    val onboardingComplete: Boolean,
    val lastGuardianPairAtMs: Long,
)

private val Context.dataStore by preferencesDataStore(name = "krypt_settings")
