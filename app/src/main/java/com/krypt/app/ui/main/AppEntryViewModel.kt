package com.krypt.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.common.Outcome
import com.krypt.app.security.LocalPinValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppEntryViewModel @Inject constructor(
    private val pinValidator: LocalPinValidator,
) : ViewModel() {

    private val _state = MutableStateFlow<AppEntryState>(AppEntryState.Idle)
    val state: StateFlow<AppEntryState> = _state.asStateFlow()

    fun submitPin(pin: CharArray) {
        if (_state.value is AppEntryState.Validating) return
        _state.value = AppEntryState.Validating
        
        viewModelScope.launch {
            val outcome = pinValidator.validate(pin)
            _state.value = when (outcome) {
                is Outcome.Ok -> AppEntryState.Success
                is Outcome.Err -> AppEntryState.Error("Incorrect PIN")
            }
        }
    }

    fun acknowledgeError() {
        if (_state.value is AppEntryState.Error) {
            _state.value = AppEntryState.Idle
        }
    }
}

sealed interface AppEntryState {
    data object Idle : AppEntryState
    data object Validating : AppEntryState
    data object Success : AppEntryState
    data class Error(val msg: String) : AppEntryState
}
