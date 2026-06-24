package com.krypt.app.ui.pairing

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.krypt.app.R
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.KPairStore
import com.krypt.app.data.GuardianPairing
import com.krypt.app.data.GuardianRepository
import com.krypt.app.data.PairingRole
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.deeplink.PairedReplyVerifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SubjectPairedConsumeViewModel @Inject constructor(
    private val verifier: PairedReplyVerifier,
    private val kPairStore: KPairStore,
    private val guardianRepo: GuardianRepository,
    private val settings: SettingsRepository,
    private val session: PairingSession,
) : ViewModel() {

    sealed interface State {
        object Loading : State
        data class Ok(val guardianName: String) : State
        data class Err(val message: String) : State
    }

    suspend fun consume(url: String): State = withContext(Dispatchers.Default) {
        val subjectId = session.subjectId() ?: return@withContext State.Err("Pairing session expired")
        val ephPrivate = session.keyPair()?.private
            ?: return@withContext State.Err("Ephemeral key not found; restart pairing")
        val out = verifier.parseAndVerify(
            url = url,
            nowSeconds = System.currentTimeMillis() / 1000L,
            myEphPrivate = ephPrivate,
            myExpectedSubjectId = subjectId,
        )
        when (out) {
            is Outcome.Ok -> {
                val (reply, kPair) = out.value
                kPairStore.save(kPair)
                guardianRepo.savePairing(
                    GuardianPairing(
                        role = PairingRole.SUBJECT_OF_GUARDIAN,
                        remoteDisplayName = reply.guardianDisplayName,
                        pubSalt = reply.guardianPubSalt,
                        kdfIterations = reply.kdfIterations,
                        pairedAt = System.currentTimeMillis(),
                    )
                )
                settings.setOnboardingComplete(true)
                session.clear()
                State.Ok(reply.guardianDisplayName)
            }
            is Outcome.Err -> State.Err(out.error.toString())
        }
    }
}

@Composable
fun SubjectPairedConsumeScreen(
    incomingUrl: String,
    viewModel: SubjectPairedConsumeViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    var state by remember { mutableStateOf<SubjectPairedConsumeViewModel.State>(SubjectPairedConsumeViewModel.State.Loading) }

    LaunchedEffect(incomingUrl) {
        state = viewModel.consume(incomingUrl)
        if (state is SubjectPairedConsumeViewModel.State.Ok) {
            delay(2000)
            onDone()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val s = state) {
            is SubjectPairedConsumeViewModel.State.Loading -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.paired_verifying), style = MaterialTheme.typography.bodyMedium)
            }
            is SubjectPairedConsumeViewModel.State.Ok -> {
                Text(
                    text = stringResource(R.string.paired_success, s.guardianName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            is SubjectPairedConsumeViewModel.State.Err -> {
                Text(
                    text = stringResource(R.string.paired_error, s.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
