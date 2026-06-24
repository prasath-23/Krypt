package com.krypt.app.ui.pairing

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.krypt.app.R
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.KPairStore
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.crypto.X25519KeyAgreement
import com.krypt.app.data.GuardianPairing
import com.krypt.app.data.GuardianRepository
import com.krypt.app.data.PairingRole
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.deeplink.PairRequest
import com.krypt.app.deeplink.PairRequestParser
import com.krypt.app.deeplink.PairedReplyBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.inject.Inject

@HiltViewModel
class GuardianPairConsumeViewModel @Inject constructor(
    private val parser: PairRequestParser,
    private val builder: PairedReplyBuilder,
    private val kdfProvider: KdfProvider,
    private val kPairStore: KPairStore,
    private val guardianRepo: GuardianRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    fun parse(url: String, nowSeconds: Long) = parser.parse(url, nowSeconds)

    suspend fun completePairing(
        incoming: PairRequest,
        pin: CharArray,
        guardianDisplayName: String,
    ): String = withContext(Dispatchers.Default) {
        val pubSalt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iterations = kdfProvider.calibrateIterationsForDevice(targetMillis = 300L)
        // In a complete impl we'd also store a KDF-hash of the PIN for
        // subsequent re-pairings; elided here (wire in WP14 T067 PIN-compare
        // path which reads from guardian_pairing).
        settings.setKdfIterations(iterations)

        val guardianKp = X25519KeyAgreement.generateEphemeralKeyPair()
        val (url, kPair) = builder.buildWithFreshAgreement(
            incoming = incoming,
            guardianDisplayName = guardianDisplayName,
            guardianEphKeyPair = guardianKp,
            guardianPubSalt = pubSalt,
            guardianKdfIterations = iterations,
        )
        kPairStore.save(kPair)
        guardianRepo.savePairing(
            GuardianPairing(
                role = PairingRole.GUARDIAN_OF_SUBJECT,
                remoteDisplayName = incoming.subjectDisplayName,
                pubSalt = pubSalt,
                kdfIterations = iterations,
                pairedAt = System.currentTimeMillis(),
            )
        )
        settings.setOnboardingComplete(true)
        pin.fill(' ')
        url
    }
}

@Composable
fun GuardianPairConsumeScreen(
    incomingUrl: String,
    viewModel: GuardianPairConsumeViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("Guardian") }
    var incomingParsed by remember { mutableStateOf<PairRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incomingUrl) {
        val out = viewModel.parse(incomingUrl, System.currentTimeMillis() / 1000L)
        when (out) {
            is Outcome.Ok -> incomingParsed = out.value
            is Outcome.Err -> error = out.error.toString()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.pair_guardian_title), style = MaterialTheme.typography.headlineSmall)
        incomingParsed?.let { req ->
            Text(
                text = stringResource(R.string.pair_guardian_body, req.subjectDisplayName),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.pair_display_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                label = { Text(stringResource(R.string.pair_pin_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pinConfirm,
                onValueChange = { pinConfirm = it.filter(Char::isDigit).take(8) },
                label = { Text(stringResource(R.string.pair_pin_confirm_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = pin.length in 4..8 && pin == pinConfirm,
                onClick = {
                    scope.launch {
                        val replyUrl = viewModel.completePairing(
                            incoming = req,
                            pin = pin.toCharArray(),
                            guardianDisplayName = displayName,
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, replyUrl)
                                },
                                null,
                            )
                        )
                        onDone()
                    }
                }
            ) { Text(stringResource(R.string.pair_guardian_confirm)) }
        }
        error?.let {
            Text(text = stringResource(R.string.pair_error, it), color = MaterialTheme.colorScheme.error)
        }
    }
}
