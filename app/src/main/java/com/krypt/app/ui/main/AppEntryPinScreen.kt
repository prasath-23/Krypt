package com.krypt.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.krypt.app.ui.link.pasteKryptLinkAction
import com.krypt.app.ui.setup.PinSetupViewModel
import kotlinx.coroutines.launch

/**
 * Guardian PIN gate in front of the Home Screen. Also offers "Paste link",
 * so a Krypt link copied from a chat can be opened when the messaging app
 * does not make it tappable.
 */
@Composable
fun AppEntryPinScreen(
    onSuccess: () -> Unit,
    viewModel: AppEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pinText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noLinkMessage = stringResource(R.string.link_not_found)
    val pasteLink = pasteKryptLinkAction {
        scope.launch { snackbarHostState.showSnackbar(noLinkMessage) }
    }

    LaunchedEffect(state) {
        when (state) {
            AppEntryState.Success -> {
                viewModel.onSuccessHandled()
                onSuccess()
            }
            is AppEntryState.WrongPin,
            is AppEntryState.LockedOut,
            AppEntryState.NotConfigured -> pinText = ""
            else -> Unit
        }
    }

    val lockout = state as? AppEntryState.LockedOut
    val lockoutLeftMs = lockoutCountdown(lockout?.retryInMs, onFinished = viewModel::acknowledgeError)
    val isValidating = state is AppEntryState.Validating

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.app_entry_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.app_entry_prompt),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(32.dp))

                OutlinedTextField(
                    value = pinText,
                    onValueChange = { input ->
                        pinText = input.filter(Char::isDigit).take(PinSetupViewModel.MAX_PIN_LEN)
                        if (state is AppEntryState.WrongPin || state is AppEntryState.NotConfigured) {
                            viewModel.acknowledgeError()
                        }
                    },
                    label = { Text(stringResource(R.string.app_entry_pin_label)) },
                    singleLine = true,
                    enabled = lockout == null && !isValidating,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )

                val error = when (val s = state) {
                    is AppEntryState.WrongPin ->
                        pluralStringResource(R.plurals.pin_error_wrong, s.attemptsLeft, s.attemptsLeft)
                    is AppEntryState.LockedOut -> pinLockoutMessage(lockoutLeftMs)
                    AppEntryState.NotConfigured -> stringResource(R.string.app_entry_not_configured)
                    else -> null
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { viewModel.submitPin(pinText.toCharArray()) },
                    enabled = pinText.length >= PinSetupViewModel.MIN_PIN_LEN &&
                        !isValidating && lockout == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isValidating) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.app_entry_unlock))
                    }
                }

                TextButton(
                    onClick = pasteLink,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.paste_link)) }
            }
        }
    }
}
