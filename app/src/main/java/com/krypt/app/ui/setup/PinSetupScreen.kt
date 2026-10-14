package com.krypt.app.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.ui.components.BodyText
import com.krypt.app.ui.components.Eyebrow
import com.krypt.app.ui.components.KryptIcon
import com.krypt.app.ui.components.KryptIcons
import com.krypt.app.ui.components.KryptPage
import com.krypt.app.ui.components.PinEntry
import com.krypt.app.ui.components.PrimaryButton
import com.krypt.app.ui.components.ScreenTitle
import com.krypt.app.ui.components.TertiaryButton
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme
import kotlinx.coroutines.delay

/**
 * Set Guardian PIN: a hand-over in three beats (docs/design-system/Screens.md).
 *
 *  1. "Give this phone to your Guardian.", then Continue.
 *  2. The PIN on Krypt's own keypad (step 1 of 2), the same PIN once more
 *     (step 2 of 2), then Save PIN. While it is saved the dots breathe.
 *  3. "Done. Hand the phone back."; a moment later [onDone] moves on to the
 *     Krypt Admin gate.
 *
 * Both PINs and the beat live only in plain [remember] state, never in saved
 * state, so rotating the phone starts the hand-over again with nothing typed.
 * The PINs are cleared after a mismatch, once the PIN is saved and when the
 * screen closes (best effort: Kotlin Strings are immutable, so the old ones
 * stay on the heap until GC).
 */
@Composable
fun PinSetupScreen(
    viewModel: PinSetupViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    var beat by remember { mutableStateOf(SetupBeat.HandOver) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val latestOnDone by rememberUpdatedState(onDone)

    DisposableEffect(Unit) {
        onDispose {
            pin = " ".repeat(pin.length)
            confirm = " ".repeat(confirm.length)
        }
    }

    LaunchedEffect(current) {
        when (current) {
            PinSetupState.Done -> {
                pin = ""
                confirm = ""
                delay(DONE_HOLD_MS)
                latestOnDone()
            }
            is PinSetupState.Error -> {
                // Every error shows on the PIN beat, even after a rotation reset the beat to the hand-over.
                confirm = ""
                // Only a failed save keeps the first PIN, for another Save PIN. The other errors are about
                // the first PIN, or the two differ, so both start again (as after a rotation cleared them).
                if (current.reason != PinSetupError.SaveFailed || pin.isEmpty()) {
                    pin = ""
                    beat = SetupBeat.Choose
                }
            }
            else -> Unit
        }
    }

    val goBack: () -> Unit = {
        viewModel.acknowledgeError()
        when (beat) {
            SetupBeat.Confirm -> {
                confirm = ""
                beat = SetupBeat.Choose
            }
            SetupBeat.Choose -> {
                pin = ""
                beat = SetupBeat.HandOver
            }
            SetupBeat.HandOver -> Unit
        }
    }
    val toConfirm: () -> Unit = {
        if (pin.length >= PinSetupViewModel.MIN_PIN_LEN) {
            viewModel.acknowledgeError()
            confirm = ""
            beat = SetupBeat.Confirm
        }
    }
    val savePin: () -> Unit = {
        if (confirm.length >= PinSetupViewModel.MIN_PIN_LEN && viewModel.state.value !is PinSetupState.Saving) {
            val pinChars = pin.toCharArray()
            val confirmChars = confirm.toCharArray()
            viewModel.save(pinChars, confirmChars)
            // save() compares the confirmation there and then, and keeps only the PIN for the key
            // derivation, which wipes it. A PIN it turned down is not kept either.
            confirmChars.fill(' ')
            if (viewModel.state.value !is PinSetupState.Saving) pinChars.fill(' ')
        }
    }

    // System Back steps back through the PIN beat like the Back button. While the PIN is being
    // saved it does nothing (the Back button is disabled then) rather than leave Krypt mid-save.
    BackHandler(enabled = beat != SetupBeat.HandOver && current !is PinSetupState.Done) {
        if (viewModel.state.value !is PinSetupState.Saving) goBack()
    }

    when {
        current is PinSetupState.Done -> DoneBeat()
        beat == SetupBeat.HandOver -> HandOverBeat(onContinue = { beat = SetupBeat.Choose })
        else -> {
            val confirming = beat == SetupBeat.Confirm
            PinBeat(
                confirming = confirming,
                entered = if (confirming) confirm else pin,
                onEnteredChange = { typed ->
                    if (confirming) confirm = typed else pin = typed
                    if (current is PinSetupState.Error) viewModel.acknowledgeError()
                },
                saving = current is PinSetupState.Saving,
                error = current as? PinSetupState.Error,
                onNext = if (confirming) savePin else toConfirm,
                onBack = goBack,
            )
        }
    }
}

/** Beat 1: the phone changes hands before anything is typed. */
@Composable
private fun HandOverBeat(onContinue: () -> Unit) {
    KryptPage(
        actions = {
            PrimaryButton(text = stringResource(R.string.action_continue), onClick = onContinue)
        },
    ) {
        Eyebrow(stringResource(R.string.eyebrow_krypt))
        Spacer(Modifier.height(KryptSpace.s2))
        ScreenTitle(stringResource(R.string.pin_setup_handover_title))
        Spacer(Modifier.height(KryptSpace.s2))
        BodyText(stringResource(R.string.pin_setup_handover_body))
    }
}

/** Beat 2: the PIN ([confirming] false), then the same PIN once more. */
@Composable
private fun PinBeat(
    confirming: Boolean,
    entered: String,
    onEnteredChange: (String) -> Unit,
    saving: Boolean,
    error: PinSetupState.Error?,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    KryptPage(
        actions = {
            PrimaryButton(
                text = stringResource(if (confirming) R.string.pin_setup_save else R.string.action_continue),
                onClick = onNext,
                enabled = entered.length >= PinSetupViewModel.MIN_PIN_LEN,
                busy = saving,
                busyText = stringResource(R.string.pin_setup_saving),
            )
            TertiaryButton(
                text = stringResource(R.string.action_back),
                onClick = onBack,
                enabled = !saving,
            )
        },
    ) {
        Eyebrow(stringResource(R.string.pin_setup_step, if (confirming) 2 else 1, SETUP_STEPS))
        Spacer(Modifier.height(KryptSpace.s2))
        ScreenTitle(
            stringResource(if (confirming) R.string.pin_setup_confirm_title else R.string.pin_setup_choose_title),
        )
        Spacer(Modifier.height(KryptSpace.s6))
        PinEntry(
            pin = entered,
            onPinChange = onEnteredChange,
            maxLength = PinSetupViewModel.MAX_PIN_LEN,
            minLength = PinSetupViewModel.MIN_PIN_LEN,
            busy = saving,
            message = when {
                error != null -> errorMessage(error.reason)
                saving -> stringResource(R.string.pin_setup_saving)
                confirming -> stringResource(R.string.pin_setup_confirm_line)
                else -> stringResource(R.string.pin_setup_choose_line)
            },
            isError = error != null,
            errorKey = error,
            onSubmit = onNext,
        )
    }
}

/** Beat 3: saved. [PinSetupScreen] moves on by itself after [DONE_HOLD_MS]. */
@Composable
private fun DoneBeat() {
    KryptPage {
        Eyebrow(stringResource(R.string.eyebrow_krypt))
        Spacer(Modifier.height(KryptSpace.s6))
        KryptIcon(
            icon = KryptIcons.CheckCircle,
            contentDescription = null,
            tint = KryptTheme.colors.granted,
            size = DONE_ICON_SIZE,
        )
        Spacer(Modifier.height(KryptSpace.s3))
        ScreenTitle(stringResource(R.string.pin_setup_done_title))
    }
}

@Composable
private fun errorMessage(reason: PinSetupError): String = stringResource(
    when (reason) {
        PinSetupError.TooShort -> R.string.pin_setup_error_too_short
        PinSetupError.NotNumeric -> R.string.pin_setup_error_not_numeric
        PinSetupError.Mismatch -> R.string.pin_setup_error_mismatch
        PinSetupError.SaveFailed -> R.string.pin_setup_error_save_failed
    },
)

/** Where the Guardian is in the hand-over; the third beat comes from [PinSetupState.Done]. */
private enum class SetupBeat { HandOver, Choose, Confirm }

/** "STEP n OF 2": the PIN, then the same PIN once more. */
private const val SETUP_STEPS = 2

/** How long "Done. Hand the phone back." stays before the Krypt Admin gate. */
private const val DONE_HOLD_MS = 1_600L

/** The `check_circle` above "Done. Hand the phone back." */
private val DONE_ICON_SIZE = 48.dp
