package com.krypt.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.ui.common.lockoutCountdown
import com.krypt.app.ui.common.pinLockoutMessage
import com.krypt.app.ui.link.shareTextChooser

/**
 * Amendment 1 Guardian PIN entry screen. Routed from [GuardianActivity]
 * when a `krypt://request?...` URL is opened on the Guardian's device.
 *
 * Inputs: the incoming URL (from the intent).
 * Outputs: opens the share sheet with the approval message when validation
 * succeeds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianPinScreen(
    incomingUrl: String,
    viewModel: GuardianPinViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(incomingUrl) { viewModel.parseIncoming(incomingUrl) }

    var pin by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose { pin = " ".repeat(pin.length) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.guardian_request_title)) })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                is GuardianPinState.Loading -> {
                    CircularProgressIndicator()
                }
                is GuardianPinState.FatalError -> {
                    Text(
                        text = messageFor(s.messageKey),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is GuardianPinState.Ready -> {
                    val lockoutLeftMs = lockoutCountdown(
                        s.lockoutRetryInMs,
                        onFinished = viewModel::lockoutElapsed,
                    )
                    LaunchedEffect(s.errorMessage) {
                        if (s.errorMessage != null) pin = ""
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                s.request.targetPackage,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.guardian_request_prompt),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it.filter(Char::isDigit).take(8)
                            if (s.errorMessage != null) viewModel.acknowledgeError()
                        },
                        label = { Text(stringResource(R.string.guardian_pin_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = s.lockoutRetryInMs == null,
                    )
                    val error = when (val err = s.errorMessage) {
                        is ErrorMessage.WrongPin ->
                            pluralStringResource(R.plurals.pin_error_wrong, err.attemptsLeft, err.attemptsLeft)
                        is ErrorMessage.Lockout -> pinLockoutMessage(lockoutLeftMs)
                        null -> null
                    }
                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { viewModel.approve(pin.toCharArray()) },
                        enabled = pin.length >= 4 && s.lockoutRetryInMs == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.guardian_send_approval)) }
                }
                is GuardianPinState.Validating -> {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.guardian_validating),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is GuardianPinState.Approved -> {
                    val message = stringResource(
                        R.string.approval_share_text,
                        s.request.targetPackage,
                        s.approvalUrl,
                    )
                    LaunchedEffect(s.approvalUrl) {
                        pin = ""
                        context.startActivity(shareTextChooser(context, message, null))
                        onDone()
                    }
                    Text(
                        text = stringResource(R.string.guardian_sent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun messageFor(key: String): String = when (key) {
    "request_expired" -> stringResource(R.string.guardian_error_expired)
    "request_corrupt" -> stringResource(R.string.guardian_error_corrupt)
    else -> stringResource(R.string.guardian_error_unreadable)
}
