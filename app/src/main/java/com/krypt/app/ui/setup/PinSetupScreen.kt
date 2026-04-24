package com.krypt.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import com.krypt.app.R

/**
 * First-time "Guardian sets PIN on Subject device" screen (Amendment 1 WP19).
 *
 * Layout:
 *   - Title + explanatory body.
 *   - Two masked numeric fields: PIN, Confirm PIN.
 *   - Save button (disabled while saving or while input is invalid).
 *   - Inline error for mismatched / too-short / non-numeric input.
 *   - Progress spinner while KDF is running.
 *
 * The typed chars live only in this Composable's [remember] state — they are
 * NOT persisted via [androidx.compose.runtime.saveable.rememberSaveable],
 * because that would write them into the process savedInstanceState bundle.
 * On dispose, the state is overwritten with blanks (best-effort; Kotlin
 * Strings are immutable so the underlying char[] on the JVM heap persists
 * until GC, per the documented limitation in WP02).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupScreen(
    viewModel: PinSetupViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    // Best-effort wipe when the screen leaves composition (rotation is
    // handled because we use `remember`, not `rememberSaveable` — rotation
    // re-enters composition, the old state is thrown away, and the user
    // retypes the PIN).
    DisposableEffect(Unit) {
        onDispose {
            pin = " ".repeat(pin.length)
            confirm = " ".repeat(confirm.length)
        }
    }

    LaunchedEffect(state) {
        if (state is PinSetupState.Done) {
            // Clear UI state, then tell the navigator we're finished.
            pin = " ".repeat(pin.length)
            confirm = " ".repeat(confirm.length)
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.pin_setup_title)) })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.pin_setup_body),
                style = MaterialTheme.typography.bodyMedium,
            )

            val isSaving = state is PinSetupState.Saving
            val isDone = state is PinSetupState.Done
            val disabled = isSaving || isDone

            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (disabled) return@OutlinedTextField
                    // Accept digits only, cap at MAX_PIN_LEN.
                    pin = it.filter { c -> c.isDigit() }
                        .take(PinSetupViewModel.MAX_PIN_LEN)
                    if (state is PinSetupState.Error) viewModel.acknowledgeError()
                },
                label = { Text(stringResource(R.string.pin_setup_pin_label)) },
                singleLine = true,
                enabled = !disabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = confirm,
                onValueChange = {
                    if (disabled) return@OutlinedTextField
                    confirm = it.filter { c -> c.isDigit() }
                        .take(PinSetupViewModel.MAX_PIN_LEN)
                    if (state is PinSetupState.Error) viewModel.acknowledgeError()
                },
                label = { Text(stringResource(R.string.pin_setup_confirm_label)) },
                singleLine = true,
                enabled = !disabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            (state as? PinSetupState.Error)?.let { err ->
                Text(
                    text = errorMessageFor(err.reason),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (isSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.pin_setup_saving),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    // Hand the VM a snapshot as CharArrays so it can wipe them
                    // after derivation. The Compose String state is cleared by
                    // LaunchedEffect on Done and by DisposableEffect on exit.
                    viewModel.save(pin.toCharArray(), confirm.toCharArray())
                },
                enabled = !disabled
                    && pin.length >= PinSetupViewModel.MIN_PIN_LEN
                    && pin == confirm,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.pin_setup_save)) }

            if (isDone) {
                Text(
                    text = stringResource(R.string.pin_setup_done),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun errorMessageFor(reason: PinSetupError): String = when (reason) {
    PinSetupError.TooShort   -> stringResource(R.string.pin_setup_error_too_short)
    PinSetupError.NotNumeric -> stringResource(R.string.pin_setup_error_not_numeric)
    PinSetupError.Mismatch   -> stringResource(R.string.pin_setup_error_mismatch)
    PinSetupError.SaveFailed -> stringResource(R.string.pin_setup_error_save_failed)
}
