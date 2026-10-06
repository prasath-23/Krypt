package com.krypt.app.ui.lock

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.common.Outcome
import com.krypt.app.data.LockReason
import com.krypt.app.data.daily.DailyAccess
import com.krypt.app.data.daily.DailyAllowances
import com.krypt.app.deeplink.UnlockRequestIssuer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives [LockScreenActivity]. The only action is asking the Guardian for
 * access: [askGuardian] issues a `krypt://request` URL (persisting its
 * OutstandingRequest) and hands it to the screen via [shareRequests].
 */
@HiltViewModel
class LockScreenViewModel @Inject constructor(
    private val requestIssuer: UnlockRequestIssuer,
    private val dailyAllowances: DailyAllowances,
) : ViewModel() {

    private val _state = MutableStateFlow(LockScreenState())
    val state: StateFlow<LockScreenState> = _state.asStateFlow()

    private val _closeRequests = Channel<Unit>(Channel.CONFLATED)

    /** Emits when the app may be used after all, e.g. an every-day approval just arrived. */
    val closeRequests: Flow<Unit> = _closeRequests.receiveAsFlow()

    init {
        // Why the app is blocked can change while this screen is up.
        viewModelScope.launch { dailyAllowances.changes.collect { refreshReason() } }
    }

    private val _shareRequests = Channel<String>(Channel.BUFFERED)

    /** One-shot request URLs for the screen to hand to the system share sheet. */
    val shareRequests: Flow<String> = _shareRequests.receiveAsFlow()

    /** Point the screen at [lockedPackage]; switching to another app starts from a clean state. */
    fun show(lockedPackage: String) {
        if (_state.value.lockedPackage != lockedPackage) {
            _state.value = LockScreenState(lockedPackage = lockedPackage)
        }
        refreshReason()
    }

    private fun refreshReason() {
        val pkg = _state.value.lockedPackage ?: return
        val reason = when (val daily = dailyAllowances.access(pkg)) {
            is DailyAccess.Available -> {
                _closeRequests.trySend(Unit)
                return
            }
            is DailyAccess.UsedUp -> LockReason.DailyUsedUp(daily.minutesPerDay, daily.lastDay)
            is DailyAccess.Paused -> LockReason.DailyPaused(daily.minutesPerDay, daily.lastDay)
            DailyAccess.None -> LockReason.Locked
        }
        if (_state.value.reason != reason) _state.value = _state.value.copy(reason = reason)
    }

    fun askGuardian() {
        val pkg = _state.value.lockedPackage ?: return
        if (_state.value.request == RequestStatus.Preparing) return
        _state.value = _state.value.copy(request = RequestStatus.Preparing)

        viewModelScope.launch {
            val status = try {
                when (val outcome = requestIssuer.issue(pkg)) {
                    is Outcome.Ok -> {
                        _shareRequests.send(outcome.value)
                        RequestStatus.Sent
                    }
                    is Outcome.Err -> RequestStatus.NotConfigured
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not issue unlock request for $pkg", e)
                RequestStatus.Failed
            }
            // The screen may have switched to another locked app meanwhile.
            if (_state.value.lockedPackage == pkg) {
                _state.value = _state.value.copy(request = status)
            }
        }
    }

    private companion object {
        const val TAG = "KryptLockScreen"
    }
}

data class LockScreenState(
    val lockedPackage: String? = null,
    val request: RequestStatus = RequestStatus.None,
    /** Why the app is blocked. */
    val reason: LockReason = LockReason.Locked,
)

enum class RequestStatus { None, Preparing, Sent, NotConfigured, Failed }
