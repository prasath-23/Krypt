package com.krypt.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.common.Outcome
import com.krypt.app.security.LocalPinValidator
import com.krypt.app.security.PinAttemptLimiter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives [AppEntryPinScreen]: the Guardian PIN gate in front of the Home
 * Screen. Every attempt counts against the shared [PinAttemptLimiter] from
 * the moment its check starts, so leaving Krypt mid-check doesn't make a
 * guess free.
 */
@HiltViewModel
class AppEntryViewModel @Inject constructor(
    private val pinValidator: LocalPinValidator,
    private val limiter: PinAttemptLimiter,
) : ViewModel() {

    private val _state = MutableStateFlow(stateForLimiter())
    val state: StateFlow<AppEntryState> = _state.asStateFlow()

    fun submitPin(pin: CharArray) {
        if (_state.value is AppEntryState.Validating) {
            pin.fill(' ')
            return
        }
        val lockedFor = limiter.lockedForMs()
        if (lockedFor > 0) {
            pin.fill(' ')
            _state.value = AppEntryState.LockedOut(lockedFor)
            return
        }
        _state.value = AppEntryState.Validating
        val attemptsLeftIfWrong = limiter.beginAttempt()

        viewModelScope.launch {
            _state.value = when (val outcome = pinValidator.validate(pin)) {
                is Outcome.Ok -> {
                    limiter.recordSuccess()
                    AppEntryState.Success
                }
                is Outcome.Err -> when (outcome.error) {
                    LocalPinValidator.ValidationError.NotConfigured -> {
                        limiter.cancelAttempt() // nothing to guess against
                        AppEntryState.NotConfigured
                    }
                    LocalPinValidator.ValidationError.WrongPin ->
                        if (attemptsLeftIfWrong == 0) {
                            AppEntryState.LockedOut(limiter.lockedForMs())
                        } else {
                            AppEntryState.WrongPin(attemptsLeftIfWrong)
                        }
                }
            }
        }
    }

    /**
     * The screen has acted on [AppEntryState.Success]. This ViewModel lives
     * as long as the activity, so without the reset the next visit to this
     * screen (after Krypt re-locks) would see the old Success and let the
     * user straight in.
     */
    fun onSuccessHandled() {
        if (_state.value == AppEntryState.Success) _state.value = AppEntryState.Idle
    }

    /** Clear an error, or a lockout that has run out, so the PIN can be typed again. */
    fun acknowledgeError() {
        when (_state.value) {
            is AppEntryState.WrongPin, AppEntryState.NotConfigured -> _state.value = AppEntryState.Idle
            is AppEntryState.LockedOut -> _state.value = stateForLimiter()
            else -> Unit
        }
    }

    private fun stateForLimiter(): AppEntryState {
        val lockedFor = limiter.lockedForMs()
        return if (lockedFor > 0) AppEntryState.LockedOut(lockedFor) else AppEntryState.Idle
    }
}

sealed interface AppEntryState {
    data object Idle : AppEntryState
    data object Validating : AppEntryState
    data object Success : AppEntryState
    data class WrongPin(val attemptsLeft: Int) : AppEntryState
    data class LockedOut(val retryInMs: Long) : AppEntryState
    data object NotConfigured : AppEntryState
}
