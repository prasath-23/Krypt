package com.krypt.app.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.deeplink.ApprovalLinkBuilder
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.deeplink.UnlockRequestParser
import com.krypt.app.deeplink.RequestParseError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinator for the Amendment 1 Guardian approval screen.
 *
 * Flow:
 *   1. Activity hands us the incoming URL via [parseIncoming].
 *   2. UI renders the parsed request (app + PIN field).
 *   3. Guardian taps Approve -> [approve] calls the validator. On success,
 *      builds the approval URL via [ApprovalLinkBuilder] and emits the URL
 *      through [state] so the Activity can fire an ACTION_SEND intent.
 *   4. Three wrong attempts within [LOCKOUT_WINDOW_MS] -> [LOCKOUT_MS]-ms
 *      lockout with countdown. Counter resets on success or after lockout.
 *
 * Rate-limit state is in-memory only (per the WP21 spec) - the Guardian
 * has to close-and-reopen the Activity to evade, which is acceptable for
 * the v1 family-safety threat model.
 */
@HiltViewModel
class GuardianPinViewModel @Inject constructor(
    private val parser: UnlockRequestParser,
    private val validator: GuardianPinValidator,
    private val approvalBuilder: ApprovalLinkBuilder,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow<GuardianPinState>(GuardianPinState.Loading)
    val state: StateFlow<GuardianPinState> = _state.asStateFlow()

    private var wrongAttempts: Int = 0
    private var firstWrongAttemptAtMs: Long = 0L
    private var lockedUntilMs: Long = 0L

    fun parseIncoming(url: String) {
        val parsed = parser.parse(url, clock.nowSeconds())
        _state.value = when (parsed) {
            is Outcome.Ok -> GuardianPinState.Ready(
                request = parsed.value,
                attemptsLeft = MAX_ATTEMPTS,
                lockoutRetryInMs = null,
                errorMessage = null,
            )
            is Outcome.Err -> GuardianPinState.FatalError(
                messageKey = mapParseError(parsed.error),
            )
        }
    }

    fun approve(pin: CharArray, grantDurationMinutes: Int = 15) {
        val ready = (_state.value as? GuardianPinState.Ready) ?: run {
            pin.fill(' ')
            return
        }
        val now = clock.nowMs()
        if (lockedUntilMs > now) {
            _state.value = ready.copy(
                lockoutRetryInMs = lockedUntilMs - now,
                errorMessage = ErrorMessage.Lockout(lockedUntilMs - now),
            )
            pin.fill(' ')
            return
        }
        _state.value = GuardianPinState.Validating(ready.request)

        viewModelScope.launch {
            val iterations = resolveIterations()
            val result = validator.validate(pin, ready.request, iterations)
            when (result) {
                is Outcome.Ok -> {
                    val masterKey = result.value
                    try {
                        val approvalUrl = approvalBuilder.build(
                            masterKey = masterKey,
                            request = ready.request,
                            grantDurationMinutes = grantDurationMinutes,
                        )
                        wrongAttempts = 0
                        firstWrongAttemptAtMs = 0L
                        _state.value = GuardianPinState.Approved(
                            request = ready.request,
                            approvalUrl = approvalUrl,
                        )
                    } finally {
                        masterKey.fill(0)
                    }
                }
                is Outcome.Err -> {
                    recordFailure(now)
                    val left = (MAX_ATTEMPTS - wrongAttempts).coerceAtLeast(0)
                    _state.value = if (left == 0) {
                        ready.copy(
                            lockoutRetryInMs = lockedUntilMs - now,
                            errorMessage = ErrorMessage.Lockout(lockedUntilMs - now),
                            attemptsLeft = 0,
                        )
                    } else {
                        ready.copy(
                            errorMessage = ErrorMessage.WrongPin(attemptsLeft = left),
                            attemptsLeft = left,
                        )
                    }
                }
            }
        }
    }

    /** Called by the screen to reset after acknowledging an error. */
    fun acknowledgeError() {
        val ready = (_state.value as? GuardianPinState.Ready) ?: return
        _state.value = ready.copy(errorMessage = null)
    }

    private fun recordFailure(nowMs: Long) {
        // Start a fresh 60-s window on the FIRST failure after a success or
        // lockout expiry.
        if (wrongAttempts == 0 || nowMs - firstWrongAttemptAtMs > LOCKOUT_WINDOW_MS) {
            wrongAttempts = 1
            firstWrongAttemptAtMs = nowMs
        } else {
            wrongAttempts += 1
        }
        if (wrongAttempts >= MAX_ATTEMPTS) {
            lockedUntilMs = nowMs + LOCKOUT_MS
        }
    }

    private suspend fun resolveIterations(): Int = try {
        val cached = settings.settings.first().kdfIterations
        cached.coerceAtLeast(KdfProvider.MIN_ITERATIONS)
    } catch (_: Throwable) {
        KdfProvider.MIN_ITERATIONS
    }

    private fun mapParseError(err: RequestParseError): String = when (err) {
        is RequestParseError.Expired -> "request_expired"
        is RequestParseError.BadPackageName -> "request_corrupt"
        is RequestParseError.BadScheme,
        is RequestParseError.WrongVersion,
        is RequestParseError.MissingParam,
        is RequestParseError.BadBase64,
        is RequestParseError.BadUuid,
        is RequestParseError.BadSaltLength,
        is RequestParseError.BadPinProofLength,
        is RequestParseError.BadTimestamp -> "request_unreadable"
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val LOCKOUT_WINDOW_MS = 60_000L
        const val LOCKOUT_MS = 60_000L
    }
}

sealed interface GuardianPinState {
    data object Loading : GuardianPinState
    data class Ready(
        val request: UnlockRequest,
        val attemptsLeft: Int,
        val lockoutRetryInMs: Long?,
        val errorMessage: ErrorMessage?,
    ) : GuardianPinState
    data class Validating(val request: UnlockRequest) : GuardianPinState
    data class Approved(
        val request: UnlockRequest,
        val approvalUrl: String,
    ) : GuardianPinState
    data class FatalError(val messageKey: String) : GuardianPinState
}

sealed interface ErrorMessage {
    data class WrongPin(val attemptsLeft: Int) : ErrorMessage
    data class Lockout(val retryInMs: Long) : ErrorMessage
}
