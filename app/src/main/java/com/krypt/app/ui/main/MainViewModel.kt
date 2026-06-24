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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val masterKeyStore: MasterKeyStore,
) : ViewModel() {

    val onboardingComplete: StateFlow<Boolean> = settings.settings
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * `true` once the Guardian has set a PIN on this device (Amendment 1).
     * Refreshed on init and whenever [refreshMasterKeyConfigured] is called
     * (e.g. immediately after the PinSetupScreen reports Done).
     */
    private val _masterKeyConfigured = MutableStateFlow(false)
    val masterKeyConfigured: StateFlow<Boolean> = _masterKeyConfigured.asStateFlow()

    init {
        refreshMasterKeyConfigured()
    }

    fun markOnboardingComplete() {
        viewModelScope.launch { settings.setOnboardingComplete(true) }
    }

    fun refreshMasterKeyConfigured() {
        viewModelScope.launch {
            _masterKeyConfigured.value = masterKeyStore.isConfigured()
        }
    }

    private val _appEntryUnlocked = MutableStateFlow(false)
    val appEntryUnlocked: StateFlow<Boolean> = _appEntryUnlocked.asStateFlow()

    fun markAppEntryUnlocked() {
        _appEntryUnlocked.value = true
    }
}
