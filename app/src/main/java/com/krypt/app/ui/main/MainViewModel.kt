package com.krypt.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val onboardingComplete: StateFlow<Boolean> = settings.settings
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun markOnboardingComplete() {
        viewModelScope.launch { settings.setOnboardingComplete(true) }
    }
}
