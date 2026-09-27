package com.krypt.app.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.deeplink.AccessChoice
import com.krypt.app.deeplink.ApprovalLinkBuilder
import com.krypt.app.deeplink.RequestParseError
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.deeplink.UnlockRequestParser
import com.krypt.app.security.PinAttemptLimiter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinator for the Amendment 1 Guardian approval screen.
 *
 * Flow:
 *   1. Activity hands us the incoming URL via [parseIncoming].
 *   2. UI renders the parsed request (app + PIN field).
 *   3. Guardian picks what to allow - one time or every day, how many
 *      minutes, for how many days ([setKind], [pickMinutes], [editMinutes],
 *      [pickDays], [editDays]; 15 minutes one time unless changed) - and
 *      taps Approve -> [approve] calls the validator with the request's own
 *      PBKDF2 iteration count (the Subject's setup value, not this device's
 *      settings). On success, builds the approval URL for that choice via
 *      [ApprovalLinkBuilder] and emits the URL through [state] so the
 *      Activity can fire an ACTION_SEND intent.
 *   4. Every attempt counts against the persistent [PinAttemptLimiter]
 *      shared with the Krypt entry screen from the moment its check starts,
 *      so leaving mid-check doesn't make a guess free. The 3rd wrong PIN
 *      starts a lockout, and reopening the screen or the app does not reset
 *      it. [lockoutElapsed] re-enables the screen once the lockout has run
 *      out.
 */
@HiltViewModel
class GuardianPinViewModel @Inject constructor(
    private val parser: UnlockRequestParser,
    private val validator: GuardianPinValidator,
    private val approvalBuilder: ApprovalLinkBuilder,
    private val limiter: PinAttemptLimiter,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow<GuardianPinState>(GuardianPinState.Loading)
    val state: StateFlow<GuardianPinState> = _state.asStateFlow()

    fun parseIncoming(url: String) {
        val parsed = parser.parse(url, clock.nowSeconds())
        _state.value = when (parsed) {
            is Outcome.Ok -> withLimiterState(
                GuardianPinState.Ready(
                    request = parsed.value,
                    attemptsLeft = PinAttemptLimiter.FIRST_LOCKOUT_AT,
                    lockoutRetryInMs = null,
                    errorMessage = null,
                    form = AccessForm(everyDayAllowed = parsed.value.supportsDaily),
                )
            )
            is Outcome.Err -> GuardianPinState.FatalError(
                messageKey = mapParseError(parsed.error),
            )
        }
    }

    /** The Guardian tapped a duration chip. */
    fun pickMinutes(minutes: Int) = updateForm { it.copy(minutes = AccessForm.Amount.Preset(minutes)) }

    /** The Guardian is typing their own number of minutes. */
    fun editMinutes(text: String) = updateForm { it.copy(minutes = AccessForm.Amount.Custom(text)) }

    /** One time, or every day (only when the child's phone understands it). */
    fun setKind(kind: AccessForm.Kind) = updateForm { it.copy(kind = kind) }

    /** The Guardian tapped a number-of-days chip. */
    fun pickDays(days: Int) = updateForm { it.copy(days = AccessForm.Amount.Preset(days)) }

    /** The Guardian is typing their own number of days. */
    fun editDays(text: String) = updateForm { it.copy(days = AccessForm.Amount.Custom(text)) }

    private fun updateForm(change: (AccessForm) -> AccessForm) {
        val ready = (_state.value as? GuardianPinState.Ready) ?: return
        _state.value = ready.copy(form = change(ready.form))
    }

    fun approve(pin: CharArray) {
        val ready = (_state.value as? GuardianPinState.Ready) ?: run {
            pin.fill(' ')
            return
        }
        val access = ready.form.choice ?: run {
            pin.fill(' ')
            return
        }
        if (limiter.lockedForMs() > 0) {
            _state.value = withLimiterState(ready)
            pin.fill(' ')
            return
        }
        _state.value = GuardianPinState.Validating(ready.request)
        val attemptsLeftIfWrong = limiter.beginAttempt()

        viewModelScope.launch {
            val result = validator.validate(pin, ready.request, ready.request.kdfIterations)
            when (result) {
                is Outcome.Ok -> {
                    val masterKey = result.value
                    try {
                        val approvalUrl = approvalBuilder.build(
                            masterKey = masterKey,
                            request = ready.request,
                            access = access,
                        )
                        limiter.recordSuccess()
                        _state.value = GuardianPinState.Approved(
                            request = ready.request,
                            approvalUrl = approvalUrl,
                            access = access,
                        )
                    } finally {
                        masterKey.fill(0)
                    }
                }
                is Outcome.Err -> {
                    _state.value = if (attemptsLeftIfWrong == 0) {
                        withLimiterState(ready.copy(attemptsLeft = 0))
                    } else {
                        ready.copy(
                            errorMessage = ErrorMessage.WrongPin(attemptsLeft = attemptsLeftIfWrong),
                            attemptsLeft = attemptsLeftIfWrong,
                            lockoutRetryInMs = null,
                        )
                    }
                }
            }
        }
    }

    /** Called by the screen to reset after acknowledging an error. */
    fun acknowledgeError() {
        val ready = (_state.value as? GuardianPinState.Ready) ?: return
        if (ready.lockoutRetryInMs != null) return
        _state.value = ready.copy(errorMessage = null)
    }

    /** The screen's lockout countdown reached zero: allow PIN entry again. */
    fun lockoutElapsed() {
        val ready = (_state.value as? GuardianPinState.Ready) ?: return
        if (ready.lockoutRetryInMs == null) return
        _state.value = withLimiterState(ready)
    }

    /** [ready] with the lockout (if any) that the shared limiter currently imposes. */
    private fun withLimiterState(ready: GuardianPinState.Ready): GuardianPinState.Ready {
        val lockedFor = limiter.lockedForMs()
        return if (lockedFor > 0) {
            ready.copy(lockoutRetryInMs = lockedFor, errorMessage = ErrorMessage.Lockout(lockedFor))
        } else {
            ready.copy(lockoutRetryInMs = null, errorMessage = null)
        }
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
        is RequestParseError.BadKdfIterations,
        is RequestParseError.BadTimestamp -> "request_unreadable"
    }
}

sealed interface GuardianPinState {
    data object Loading : GuardianPinState
    data class Ready(
        val request: UnlockRequest,
        val attemptsLeft: Int,
        val lockoutRetryInMs: Long?,
        val errorMessage: ErrorMessage?,
        /** What the approval will allow; kept across wrong PINs and lockouts. */
        val form: AccessForm = AccessForm(),
    ) : GuardianPinState
    data class Validating(val request: UnlockRequest) : GuardianPinState
    data class Approved(
        val request: UnlockRequest,
        val approvalUrl: String,
        /** What the approval allows. */
        val access: AccessChoice,
    ) : GuardianPinState
    data class FatalError(val messageKey: String) : GuardianPinState
}

sealed interface ErrorMessage {
    data class WrongPin(val attemptsLeft: Int) : ErrorMessage
    data class Lockout(val retryInMs: Long) : ErrorMessage
}
