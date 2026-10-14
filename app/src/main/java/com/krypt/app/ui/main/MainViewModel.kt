package com.krypt.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.security.MasterKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val masterKeyStore: MasterKeyStore,
) : ViewModel() {

    /** Null until the settings have been read once. */
    private val storedOnboardingComplete: StateFlow<Boolean?> = settings.settings
        .map<_, Boolean?> { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val onboardingComplete: StateFlow<Boolean> = storedOnboardingComplete
        .map { it == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * `true` once the Guardian has set a PIN on this device (Amendment 1).
     * Refreshed on init and whenever [refreshMasterKeyConfigured] is called
     * (e.g. immediately after the PinSetupScreen reports Done).
     */
    private val _masterKeyConfigured = MutableStateFlow(false)
    val masterKeyConfigured: StateFlow<Boolean> = _masterKeyConfigured.asStateFlow()

    private val masterKeyChecked = MutableStateFlow(false)

    /**
     * `false` until the settings and the PIN store have both been read once.
     * Until then the route shows only the background, so no screen (such as
     * the first-run welcome) flashes before the right one.
     */
    val ready: StateFlow<Boolean> = combine(storedOnboardingComplete, masterKeyChecked) { onboarding, checked ->
        onboarding != null && checked
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        refreshMasterKeyConfigured()
    }

    fun markOnboardingComplete() {
        viewModelScope.launch { settings.setOnboardingComplete(true) }
    }

    fun refreshMasterKeyConfigured() {
        viewModelScope.launch {
            _masterKeyConfigured.value = masterKeyStore.isConfigured()
            masterKeyChecked.value = true
        }
    }

    private val _appEntryUnlocked = MutableStateFlow(false)

    /** `true` once the Guardian PIN was entered; reset whenever Krypt leaves the foreground. */
    val appEntryUnlocked: StateFlow<Boolean> = _appEntryUnlocked.asStateFlow()

    fun markAppEntryUnlocked() {
        _appEntryUnlocked.value = true
    }

    /** Close the Home Screen again, so returning to Krypt needs the Guardian PIN. */
    fun lockAppEntry() {
        _appEntryUnlocked.value = false
    }
}
