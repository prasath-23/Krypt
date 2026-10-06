package com.krypt.app.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.deeplink.AccessChoice
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
                .imePadding()
                .verticalScroll(rememberScrollState())
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
                    AllowFor(
                        form = s.form,
                        onKind = viewModel::setKind,
                        onPickMinutes = viewModel::pickMinutes,
                        onEditMinutes = viewModel::editMinutes,
                        onPickDays = viewModel::pickDays,
                        onEditDays = viewModel::editDays,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it.filter(Char::isDigit).take(8)
                            if (s.errorMessage != null) viewModel.acknowledgeError()
                        },
                        label = { Text(stringResource(R.string.guardian_pin_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().testTag(PIN_FIELD_TAG),
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
                        enabled = pin.length >= 4 && s.lockoutRetryInMs == null && s.form.choice != null,
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
                        accessText(s.access),
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

/** Test tags for the screen's text fields, which UI tests can't tell apart otherwise. */
const val PIN_FIELD_TAG = "guardian_pin"
const val MINUTES_FIELD_TAG = "guardian_minutes"
const val DAYS_FIELD_TAG = "guardian_days"

/** What the approval allows: one time or every day, how long, for how many days. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AllowFor(
    form: AccessForm,
    onKind: (AccessForm.Kind) -> Unit,
    onPickMinutes: (Int) -> Unit,
    onEditMinutes: (String) -> Unit,
    onPickDays: (Int) -> Unit,
    onEditDays: (String) -> Unit,
) {
    if (form.everyDayAllowed) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.kind == AccessForm.Kind.ONE_TIME,
                onClick = { onKind(AccessForm.Kind.ONE_TIME) },
                label = { Text(stringResource(R.string.guardian_allow_one_time)) },
            )
            FilterChip(
                selected = form.kind == AccessForm.Kind.EVERY_DAY,
                onClick = { onKind(AccessForm.Kind.EVERY_DAY) },
                label = { Text(stringResource(R.string.guardian_allow_every_day)) },
            )
        }
    }
    Text(
        text = stringResource(R.string.guardian_allow_for),
        style = MaterialTheme.typography.titleSmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccessForm.MINUTE_PRESETS.forEach { minutes ->
            FilterChip(
                selected = form.minutes == AccessForm.Amount.Preset(minutes),
                onClick = { onPickMinutes(minutes) },
                label = { Text(chipLabel(minutes)) },
            )
        }
        FilterChip(
            selected = form.minutes is AccessForm.Amount.Custom,
            onClick = { if (form.minutes !is AccessForm.Amount.Custom) onEditMinutes("") },
            label = { Text(stringResource(R.string.guardian_set_minutes)) },
        )
    }
    val custom = form.minutes as? AccessForm.Amount.Custom
    if (custom != null) {
        OutlinedTextField(
            value = custom.text,
            onValueChange = { onEditMinutes(it.filter(Char::isDigit).take(4)) },
            label = { Text(stringResource(R.string.guardian_minutes_label)) },
            isError = form.minutesInvalid,
            supportingText = if (form.minutesInvalid) {
                { Text(stringResource(R.string.guardian_minutes_error)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(MINUTES_FIELD_TAG),
        )
    }
    if (form.kind == AccessForm.Kind.EVERY_DAY) {
        EveryDayFor(form, onPickDays, onEditDays)
    }
    form.choice?.let { access ->
        Text(
            text = stringResource(R.string.guardian_allows, accessText(access)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** "For": how many days an every-day approval lasts. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EveryDayFor(
    form: AccessForm,
    onPickDays: (Int) -> Unit,
    onEditDays: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.guardian_for_days),
        style = MaterialTheme.typography.titleSmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccessForm.DAY_PRESETS.forEach { days ->
            FilterChip(
                selected = form.days == AccessForm.Amount.Preset(days),
                onClick = { onPickDays(days) },
                label = { Text(pluralStringResource(R.plurals.duration_days, days, days)) },
            )
        }
        FilterChip(
            selected = form.days is AccessForm.Amount.Custom,
            onClick = { if (form.days !is AccessForm.Amount.Custom) onEditDays("") },
            label = { Text(stringResource(R.string.guardian_set_days)) },
        )
    }
    val custom = form.days as? AccessForm.Amount.Custom
    if (custom != null) {
        OutlinedTextField(
            value = custom.text,
            onValueChange = { onEditDays(it.filter(Char::isDigit).take(3)) },
            label = { Text(stringResource(R.string.guardian_days_label)) },
            isError = form.daysInvalid,
            supportingText = if (form.daysInvalid) {
                { Text(stringResource(R.string.guardian_days_error)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(DAYS_FIELD_TAG),
        )
    }
    Text(
        text = stringResource(R.string.guardian_daily_hint),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun chipLabel(minutes: Int): String =
    if (minutes % 60 == 0) {
        pluralStringResource(R.plurals.duration_hours, minutes / 60, minutes / 60)
    } else {
        pluralStringResource(R.plurals.chip_minutes, minutes, minutes)
    }

/** What an approval allows, e.g. "30 minutes, one time" or "1 hour a day for 7 days". */
@Composable
private fun accessText(access: AccessChoice): String = when (access) {
    is AccessChoice.OneTime -> stringResource(R.string.access_one_time, durationText(access.minutes))
    is AccessChoice.EveryDay -> stringResource(
        R.string.access_every_day,
        durationText(access.minutes),
        pluralStringResource(R.plurals.duration_days, access.days, access.days),
    )
}

@Composable
private fun durationText(minutes: Int): String =
    if (minutes % 60 == 0) {
        pluralStringResource(R.plurals.duration_hours, minutes / 60, minutes / 60)
    } else {
        pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    }

@Composable
private fun messageFor(key: String): String = when (key) {
    "request_expired" -> stringResource(R.string.guardian_error_expired)
    "request_corrupt" -> stringResource(R.string.guardian_error_corrupt)
    else -> stringResource(R.string.guardian_error_unreadable)
}
