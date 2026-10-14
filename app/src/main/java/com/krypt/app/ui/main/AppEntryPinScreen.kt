package com.krypt.app.ui.main

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.ui.components.Eyebrow
import com.krypt.app.ui.components.KryptIcons
import com.krypt.app.ui.components.KryptPage
import com.krypt.app.ui.components.PinEntry
import com.krypt.app.ui.components.PrimaryButton
import com.krypt.app.ui.components.ScreenTitle
import com.krypt.app.ui.components.TertiaryButton
import com.krypt.app.ui.link.pasteKryptLinkAction
import com.krypt.app.ui.setup.PinSetupViewModel
import com.krypt.app.ui.theme.KryptSpace
import kotlinx.coroutines.launch

/**
 * The Krypt Admin gate in front of Home (docs/design-system/Screens.md): the
 * Guardian PIN on Krypt's own keypad, then Continue. A lockout puts its
 * countdown in place of the message line. Paste a link opens a Krypt link
 * copied from a chat, for messengers that don't make it tappable.
 *
 * The PIN lives only in plain [remember] state, never in saved state. It is
 * cleared the moment it is sent, after a wrong PIN and when the screen closes
 * (best effort: the old String stays on the heap until GC).
 */
@Composable
fun AppEntryPinScreen(
    onSuccess: () -> Unit,
    viewModel: AppEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    var pin by remember { mutableStateOf("") }
    // The length of the PIN being checked, so its dots stay filled and breathe; the PIN itself is already cleared.
    var sentDigits by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkNotFound = stringResource(R.string.link_not_found)
    val pasteLink = pasteKryptLinkAction {
        scope.launch { snackbarHostState.showSnackbar(linkNotFound) }
    }

    DisposableEffect(Unit) {
        onDispose { pin = " ".repeat(pin.length) }
    }

    LaunchedEffect(current) {
        if (current !is AppEntryState.Validating) sentDigits = 0
        when (current) {
            AppEntryState.Success -> {
                viewModel.onSuccessHandled()
                onSuccess()
            }
            is AppEntryState.WrongPin,
            is AppEntryState.LockedOut,
            AppEntryState.NotConfigured -> pin = ""
            else -> Unit
        }
    }

    val checking = current is AppEntryState.Validating
    val lockout = current as? AppEntryState.LockedOut
    val isError = current is AppEntryState.WrongPin || current is AppEntryState.NotConfigured
    // Reads the live PIN and state, so a double tap or Enter never sends an empty PIN (the check rejects one).
    val submit: () -> Unit = {
        val now = viewModel.state.value
        if (pin.length >= PinSetupViewModel.MIN_PIN_LEN &&
            now !is AppEntryState.Validating &&
            now !is AppEntryState.LockedOut
        ) {
            sentDigits = pin.length
            viewModel.submitPin(pin.toCharArray())
            pin = ""
        }
    }

    KryptPage(
        snackbarHostState = snackbarHostState,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.action_continue),
                onClick = submit,
                enabled = pin.length >= PinSetupViewModel.MIN_PIN_LEN && !checking && lockout == null,
                busy = checking,
                busyText = stringResource(R.string.guardian_checking),
            )
            TertiaryButton(
                text = stringResource(R.string.paste_link),
                onClick = pasteLink,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                icon = KryptIcons.Link,
            )
            // HintText's style, centred under Paste a link.
            Text(
                text = stringResource(R.string.paste_link_hint),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
    ) {
        Eyebrow(stringResource(R.string.app_entry_eyebrow))
        Spacer(Modifier.height(KryptSpace.s2))
        ScreenTitle(stringResource(R.string.app_entry_title))
        Spacer(Modifier.height(KryptSpace.s6))
        PinEntry(
            pin = if (sentDigits > 0) " ".repeat(sentDigits) else pin,
            onPinChange = { typed ->
                pin = typed
                if (current is AppEntryState.WrongPin || current is AppEntryState.NotConfigured) {
                    viewModel.acknowledgeError()
                }
            },
            maxLength = PinSetupViewModel.MAX_PIN_LEN,
            minLength = PinSetupViewModel.MIN_PIN_LEN,
            busy = checking,
            message = when (current) {
                AppEntryState.Validating -> stringResource(R.string.pin_checking)
                is AppEntryState.WrongPin ->
                    pluralStringResource(R.plurals.pin_error_wrong, current.attemptsLeft, current.attemptsLeft)
                AppEntryState.NotConfigured -> stringResource(R.string.app_entry_not_configured)
                else -> stringResource(R.string.app_entry_prompt)
            },
            isError = isError,
            errorKey = current.takeIf { isError },
            lockoutMs = lockout?.retryInMs,
            onLockoutElapsed = viewModel::acknowledgeError,
            onSubmit = submit,
        )
    }
}
