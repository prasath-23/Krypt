package com.krypt.app.ui.guardian

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.common.Outcome
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.deeplink.ApprovalConsumer
import com.krypt.app.deeplink.ApprovalError
import com.krypt.app.deeplink.ApprovalOutcome
import com.krypt.app.service.OverlayManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GuardianApprovalViewModel @Inject constructor(
    private val consumer: ApprovalConsumer,
    private val sessionStore: LockerSessionStore,
    private val overlayManager: OverlayManager,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Success(val outcome: ApprovalOutcome) : State
        data class Error(val error: ApprovalError) : State
    }

    @androidx.compose.runtime.Stable
    class StateHolder {
        var state by androidx.compose.runtime.mutableStateOf<State>(State.Loading)
    }

    fun consume(url: String, holder: StateHolder) {
        viewModelScope.launch {
            when (val out = consumer.consume(url)) {
                is Outcome.Ok -> {
                    // FR-013 + WP10 integration: cache the grant, hide the
                    // overlay immediately rather than wait for the
                    // Accessibility Service's Flow collector.
                    sessionStore.recordGrant(out.value.targetPackage, out.value.grantExpiresAtMs)
                    overlayManager.hide()
                    holder.state = State.Success(out.value)
                }
                is Outcome.Err -> holder.state = State.Error(out.error)
            }
        }
    }
}

@Composable
fun GuardianApprovalConsumeScreen(
    incomingUrl: String,
    viewModel: GuardianApprovalViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val holder = remember { GuardianApprovalViewModel.StateHolder() }
    LaunchedEffect(incomingUrl) { viewModel.consume(incomingUrl, holder) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = holder.state) {
            is GuardianApprovalViewModel.State.Loading -> {
                CircularProgressIndicator()
                Text("Decrypting approval...", style = MaterialTheme.typography.bodyMedium)
            }
            is GuardianApprovalViewModel.State.Success -> {
                Text(
                    text = "Unlocked ${s.outcome.targetPackage}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                LaunchedEffect(s) {
                    delay(2_000)
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(s.outcome.targetPackage)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(launchIntent)
                    }
                    (context as? Activity)?.finish()
                    onDone()
                }
            }
            is GuardianApprovalViewModel.State.Error -> {
                Text(
                    text = messageFor(s.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun messageFor(error: ApprovalError): String = when (error) {
    ApprovalError.BadScheme -> "This link is not a Krypt approval."
    ApprovalError.WrongVersion -> "This approval uses a newer Krypt protocol."
    is ApprovalError.MissingParam -> "Approval missing parameter: ${error.name}"
    ApprovalError.BadBase64 -> "Approval payload is malformed."
    ApprovalError.BadUuid -> "Approval request ID is malformed."
    ApprovalError.UnmatchedRequest -> "This approval doesn't match any pending unlock request (or was already used)."
    ApprovalError.RequestExpired -> "Original request expired; please ask again."
    ApprovalError.CipherDecryptFailed -> "Approval failed cryptographic verification; it may have been tampered with."
    ApprovalError.PayloadInconsistent -> "Approval payload inconsistent with request."
    ApprovalError.NotPaired -> "This device is not paired with any Guardian."
}
