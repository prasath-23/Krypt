package com.krypt.app.guardian

import android.content.ActivityNotFoundException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.deeplink.AccessChoice
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.ui.components.BodyText
import com.krypt.app.ui.components.ChoiceChip
import com.krypt.app.ui.components.ErrorPanel
import com.krypt.app.ui.components.Eyebrow
import com.krypt.app.ui.components.InlineError
import com.krypt.app.ui.components.KryptIcons
import com.krypt.app.ui.components.KryptPage
import com.krypt.app.ui.components.KryptSheet
import com.krypt.app.ui.components.NotePanel
import com.krypt.app.ui.components.PinEntry
import com.krypt.app.ui.components.PrimaryButton
import com.krypt.app.ui.components.ScreenTitle
import com.krypt.app.ui.components.SegmentedChoice
import com.krypt.app.ui.components.Statement
import com.krypt.app.ui.components.StyledArg
import com.krypt.app.ui.components.TertiaryButton
import com.krypt.app.ui.components.styledFormat
import com.krypt.app.ui.link.shareTextChooser
import com.krypt.app.ui.theme.GeistMono
import com.krypt.app.ui.theme.KryptMotion
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme
import kotlinx.coroutines.delay

/**
 * The Guardian's approval screen (docs/design-system/components/GuardianApproval),
 * on the Guardian's own phone. Routed from
 * [com.krypt.app.ui.guardian.GuardianActivity] when a `krypt://request` link
 * is opened. Two steps, so the keypad is never inside a scrolling page.
 * Step 1 shows which app, how old the request is, what to allow
 * (DurationChips) and the choice read back as one sentence, then
 * **Continue**. Step 2 is a sheet with that sentence, the Guardian PIN and
 * **Approve and send**, which hands the reply to the share sheet.
 *
 * The PIN lives only in this composable's memory, never in saved state. It
 * is cleared after every submit, after a wrong PIN, when the sheet is
 * dismissed and when the screen closes, and the ViewModel gets it as a
 * [CharArray] that it wipes.
 */
@Composable
fun GuardianPinScreen(
    incomingUrl: String,
    viewModel: GuardianPinViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val currentOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(incomingUrl) { viewModel.parseIncoming(incomingUrl) }

    // Keyed by the link, so a new request (onNewIntent) never inherits typed digits.
    var pin by remember(incomingUrl) { mutableStateOf("") }
    // How many digits went to be checked, so the dots can breathe once the PIN itself is gone.
    var checkingLength by remember(incomingUrl) { mutableIntStateOf(0) }
    DisposableEffect(incomingUrl) {
        onDispose { pin = " ".repeat(pin.length) }
    }
    // Step 2, the PIN sheet, is up. Only that is saved (it survives rotation), never the PIN.
    var confirming by rememberSaveable(incomingUrl) { mutableStateOf(false) }

    val s = state
    // While the PIN is checked, the form stays on screen as it was, disabled.
    val lastReady = remember(incomingUrl) { mutableStateOf<GuardianPinState.Ready?>(null) }
    if (s is GuardianPinState.Ready) SideEffect { lastReady.value = s }
    val validating = s is GuardianPinState.Validating
    val ready: GuardianPinState.Ready? = when (s) {
        is GuardianPinState.Ready -> s
        is GuardianPinState.Validating -> lastReady.value ?: s.placeholderForm()
        else -> null
    }

    val errorMessage = (s as? GuardianPinState.Ready)?.errorMessage
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) pin = ""
    }

    val request = when (s) {
        is GuardianPinState.Ready -> s.request
        is GuardianPinState.Validating -> s.request
        is GuardianPinState.Approved -> s.request
        else -> null
    }
    val appLabel = rememberAppLabel(request?.targetPackage)

    // The sheet belongs to a live request: it goes once the reply is sent, or
    // when the request fails or runs out (step 1 then says why).
    LaunchedEffect(ready == null) {
        if (ready == null) confirming = false
    }
    // A request lasts 5 minutes. If it runs out while this screen is up, say so
    // then, rather than at the next tap.
    LaunchedEffect(request) {
        while (request != null) {
            delay(TICK_MS)
            viewModel.expireIfDue()
        }
    }

    // One rule for the button and for Enter on a hardware keyboard: every
    // approve() call counts against the shared PIN attempt limit.
    fun canApprove(): Boolean =
        ready != null &&
            !validating &&
            pin.length >= PIN_MIN_LENGTH &&
            ready.lockoutRetryInMs == null &&
            ready.form.choice != null
    val submit: () -> Unit = {
        if (canApprove()) {
            checkingLength = pin.length
            viewModel.approve(pin.toCharArray())
            pin = ""
        }
    }

    // Continue needs a whole choice: an invalid custom value disables it.
    val canContinue = ready != null && !validating && ready.form.choice != null
    val openSheet: () -> Unit = {
        if (canContinue) {
            // Put away a keyboard left open by a Custom field; the sheet has Krypt's keypad.
            keyboard?.hide()
            focusManager.clearFocus()
            confirming = true
        }
    }

    val actions: (@Composable ColumnScope.() -> Unit)? = if (ready == null) {
        null
    } else {
        {
            PrimaryButton(
                text = stringResource(R.string.action_continue),
                onClick = openSheet,
                enabled = canContinue,
            )
        }
    }

    KryptPage(actions = actions) {
        Eyebrow(text = stringResource(R.string.guardian_eyebrow))
        when (s) {
            GuardianPinState.Loading -> ProgressLine()
            is GuardianPinState.FatalError -> {
                Spacer(Modifier.height(KryptSpace.s6))
                ErrorPanel(text = fatalMessage(s.messageKey))
            }
            is GuardianPinState.Approved -> {
                val message = stringResource(
                    R.string.approval_share_text,
                    appLabel ?: s.request.targetPackage,
                    accessText(s.access),
                    s.approvalUrl,
                )
                val shareTitle = stringResource(R.string.guardian_share_title)
                LaunchedEffect(s.approvalUrl) {
                    pin = ""
                    try {
                        context.startActivity(shareTextChooser(context, message, shareTitle))
                    } catch (noChooser: ActivityNotFoundException) {
                        // Nothing on this phone can share text; the screen stays as it is.
                    }
                    currentOnDone()
                }
                Spacer(Modifier.height(KryptSpace.s2))
                Statement(text = stringResource(R.string.guardian_sent_title))
                Spacer(Modifier.height(KryptSpace.s2))
                BodyText(text = stringResource(R.string.guardian_sent_body))
            }
            is GuardianPinState.Ready, is GuardianPinState.Validating -> ready?.let { shown ->
                RequestHeader(request = shown.request, appLabel = appLabel)
                Spacer(Modifier.height(KryptSpace.s8))
                DurationChips(
                    form = shown.form,
                    enabled = !validating,
                    onKind = viewModel::setKind,
                    onPickMinutes = viewModel::pickMinutes,
                    onEditMinutes = viewModel::editMinutes,
                    onPickDays = viewModel::pickDays,
                    onEditDays = viewModel::editDays,
                )
                // The live sentence; it hides while a custom value is invalid.
                shown.form.choice?.let { choice ->
                    Spacer(Modifier.height(KryptSpace.s8))
                    SummarySentence(
                        choice = choice,
                        appLabel = appLabel,
                        packageName = shown.request.targetPackage,
                    )
                }
            }
        }
    }

    if (confirming && ready != null) {
        val wrongPin = ready.errorMessage as? ErrorMessage.WrongPin
        PinSheet(
            choice = ready.form.choice,
            appLabel = appLabel,
            packageName = ready.request.targetPackage,
            validating = validating,
            canApprove = canApprove(),
            onSubmit = submit,
            onDismiss = {
                // Back to step 1: the choice stays, the PIN goes.
                confirming = false
                pin = ""
            },
        ) {
            PinEntry(
                pin = if (validating) CHECKING_DOT.repeat(checkingLength) else pin,
                onPinChange = { typed ->
                    pin = typed
                    if (ready.errorMessage is ErrorMessage.WrongPin) viewModel.acknowledgeError()
                },
                maxLength = PIN_MAX_LENGTH,
                minLength = PIN_MIN_LENGTH,
                busy = validating,
                message = when {
                    validating -> stringResource(R.string.pin_checking)
                    wrongPin != null -> pluralStringResource(
                        R.plurals.pin_error_wrong,
                        wrongPin.attemptsLeft,
                        wrongPin.attemptsLeft,
                    )
                    else -> stringResource(R.string.guardian_pin_line)
                },
                isError = !validating && wrongPin != null,
                errorKey = if (validating) null else ready.errorMessage,
                lockoutMs = if (validating) null else ready.lockoutRetryInMs,
                onLockoutElapsed = viewModel::lockoutElapsed,
                lockoutReplacesKeypad = true,
                onSubmit = submit,
                tag = PIN_FIELD_TAG,
            )
        }
    }
}

/**
 * Step 2 (GuardianApproval): the choice read back, [pinEntry] and Approve
 * and send, in a sheet over step 1. It fits a 320 x 640dp phone without
 * scrolling. While the PIN is checked it can't be dismissed: the approval is
 * already on its way. Screenshots of it are blocked, as of the whole screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(
    choice: AccessChoice?,
    appLabel: String?,
    packageName: String,
    validating: Boolean,
    canApprove: Boolean,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    pinEntry: @Composable () -> Unit,
) {
    val checking by rememberUpdatedState(validating)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !checking },
    )
    KryptSheet(
        onDismissRequest = { if (!checking) onDismiss() },
        sheetState = sheetState,
        secure = true,
    ) {
        Column(
            // Only at very large text sizes is there more than fits; then it
            // scrolls rather than cutting off the keypad.
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(KryptSpace.s4),
        ) {
            choice?.let { SummarySentence(choice = it, appLabel = appLabel, packageName = packageName) }
            pinEntry()
            PrimaryButton(
                text = stringResource(R.string.guardian_approve),
                onClick = onSubmit,
                enabled = canApprove,
                busy = validating,
                busyText = stringResource(R.string.guardian_checking),
                icon = KryptIcons.Share,
            )
        }
    }
}

/** Test tags for the screen's inputs, which UI tests can't tell apart otherwise. */
const val PIN_FIELD_TAG = "guardian_pin"
const val MINUTES_FIELD_TAG = "guardian_minutes"
const val DAYS_FIELD_TAG = "guardian_days"

/**
 * The app the request is for, the "Why only a package name?" sheet when it
 * isn't installed here, and how old the request is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestHeader(request: UnlockRequest, appLabel: String?) {
    var explainPackage by remember(request.targetPackage) { mutableStateOf(false) }
    Spacer(Modifier.height(KryptSpace.s2))
    if (appLabel != null) {
        ScreenTitle(text = appLabel)
    } else {
        val title = MaterialTheme.typography.titleLarge
        Text(
            text = request.targetPackage,
            modifier = Modifier.semantics { heading() },
            style = KryptTheme.text.monoPackage.copy(fontSize = title.fontSize, lineHeight = title.lineHeight),
            color = MaterialTheme.colorScheme.onSurface,
        )
        TertiaryButton(
            text = stringResource(R.string.guardian_why_package),
            onClick = { explainPackage = true },
            // Lines the icon up with the text above it.
            modifier = Modifier.offset(x = -KryptSpace.s3),
            icon = KryptIcons.Info,
        )
    }
    Spacer(Modifier.height(KryptSpace.s2))
    RequestAge(request)

    if (explainPackage) {
        KryptSheet(onDismissRequest = { explainPackage = false }) {
            Text(
                text = stringResource(R.string.guardian_why_package),
                modifier = Modifier.semantics { heading() },
                style = KryptTheme.text.headline,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BodyText(text = stringResource(R.string.guardian_why_package_body))
        }
    }
}

/**
 * "Sent 1 min ago · expires in 4:00", ticking each second; "· expired" once
 * the request's lifetime is over. Uses this phone's clock against the time
 * the request was made, so it can be off by the clock difference between
 * the two phones.
 */
@Composable
private fun RequestAge(request: UnlockRequest) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS - System.currentTimeMillis() % TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }
    val nowSeconds = nowMs / 1_000L
    val sentSecondsAgo = (nowSeconds - request.issuedAt).coerceAtLeast(0L)
    val secondsLeft = (request.expiresAtSeconds - nowSeconds)
        .coerceAtMost(request.ttlSeconds)
        .coerceAtLeast(0L)
    val sent = if (sentSecondsAgo < 60L) {
        stringResource(R.string.guardian_sent_just_now)
    } else {
        val minutes = (sentSecondsAgo / 60L).toInt()
        pluralStringResource(R.plurals.guardian_sent_ago, minutes, minutes)
    }
    val line = if (secondsLeft > 0L) {
        styledFormat(
            stringResource(R.string.guardian_age),
            StyledArg(sent),
            StyledArg(clockText(secondsLeft), KryptTheme.text.monoTime.toSpanStyle()),
        )
    } else {
        styledFormat(stringResource(R.string.guardian_age_expired), StyledArg(sent))
    }
    Text(
        text = line,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * DurationChips: how often (only when the request says the phone supports
 * daily time), for how long, and for how many days. While the PIN is
 * checked ([enabled] false) taps and typing are ignored.
 */
@Composable
private fun DurationChips(
    form: AccessForm,
    enabled: Boolean,
    onKind: (AccessForm.Kind) -> Unit,
    onPickMinutes: (Int) -> Unit,
    onEditMinutes: (String) -> Unit,
    onPickDays: (Int) -> Unit,
    onEditDays: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val kinds = form.kinds
        if (kinds.size > 1) {
            val howOften = stringResource(R.string.guardian_how_often)
            FieldLabel(text = howOften)
            Spacer(Modifier.height(KryptSpace.s2))
            SegmentedChoice(
                options = kinds,
                selected = form.kind,
                onSelect = { kind -> if (enabled) onKind(kind) },
                label = { kind -> kindLabel(kind) },
                groupLabel = howOften,
            )
        }

        // For good has no duration: no minutes to choose.
        AnimatedVisibility(
            visible = form.kind != AccessForm.Kind.FOR_GOOD,
            enter = Reveal,
            exit = Conceal,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (kinds.size > 1) Spacer(Modifier.height(KryptSpace.s6))
                FieldLabel(text = stringResource(R.string.guardian_how_long))
                Spacer(Modifier.height(KryptSpace.s2))
                ChipRow {
                    AccessForm.MINUTE_PRESETS.forEach { minutes ->
                        ChoiceChip(
                            text = minutesChipLabel(minutes),
                            selected = form.minutes == AccessForm.Amount.Preset(minutes),
                            onClick = { if (enabled) onPickMinutes(minutes) },
                        )
                    }
                    ChoiceChip(
                        text = stringResource(R.string.guardian_custom),
                        selected = form.minutes is AccessForm.Amount.Custom,
                        onClick = { if (enabled && form.minutes !is AccessForm.Amount.Custom) onEditMinutes("") },
                    )
                }
                AnimatedVisibility(
                    visible = form.minutes is AccessForm.Amount.Custom,
                    enter = Reveal,
                    exit = Conceal,
                ) {
                    CustomAmountField(
                        text = (form.minutes as? AccessForm.Amount.Custom)?.text.orEmpty(),
                        onTextChange = { typed -> onEditMinutes(typed.filter(Char::isDigit).take(MINUTES_MAX_DIGITS)) },
                        label = stringResource(R.string.guardian_minutes_label),
                        suffix = stringResource(R.string.guardian_minutes_suffix),
                        error = stringResource(R.string.guardian_minutes_error),
                        isError = form.minutesInvalid,
                        readOnly = !enabled,
                        tag = MINUTES_FIELD_TAG,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = form.kind == AccessForm.Kind.EVERY_DAY,
            enter = Reveal,
            exit = Conceal,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(KryptSpace.s6))
                FieldLabel(text = stringResource(R.string.guardian_how_many_days))
                Spacer(Modifier.height(KryptSpace.s2))
                ChipRow {
                    AccessForm.DAY_PRESETS.forEach { days ->
                        ChoiceChip(
                            text = daysWords(days),
                            selected = form.days == AccessForm.Amount.Preset(days),
                            onClick = { if (enabled) onPickDays(days) },
                        )
                    }
                    ChoiceChip(
                        text = stringResource(R.string.guardian_custom),
                        selected = form.days is AccessForm.Amount.Custom,
                        onClick = { if (enabled && form.days !is AccessForm.Amount.Custom) onEditDays("") },
                    )
                }
                AnimatedVisibility(
                    visible = form.days is AccessForm.Amount.Custom,
                    enter = Reveal,
                    exit = Conceal,
                ) {
                    CustomAmountField(
                        text = (form.days as? AccessForm.Amount.Custom)?.text.orEmpty(),
                        onTextChange = { typed -> onEditDays(typed.filter(Char::isDigit).take(DAYS_MAX_DIGITS)) },
                        label = stringResource(R.string.guardian_days_label),
                        suffix = stringResource(R.string.guardian_days_suffix),
                        error = stringResource(R.string.guardian_days_error),
                        isError = form.daysInvalid,
                        readOnly = !enabled,
                        tag = DAYS_FIELD_TAG,
                    )
                }
                Spacer(Modifier.height(KryptSpace.s3))
                NotePanel(text = stringResource(R.string.guardian_daily_note))
            }
        }
    }
}

/** The small label above a group of choices, e.g. "For how long". */
@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Chips that wrap onto more rows as needed, read by TalkBack as one choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s2),
        verticalArrangement = Arrangement.spacedBy(KryptSpace.s2),
    ) {
        content()
    }
}

/** The field a Custom chip reveals: digits only, with its unit after the number. */
@Composable
private fun CustomAmountField(
    text: String,
    onTextChange: (String) -> Unit,
    label: String,
    suffix: String,
    error: String,
    isError: Boolean,
    readOnly: Boolean,
    tag: String,
) {
    val focusManager = LocalFocusManager.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(KryptSpace.s3))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
            readOnly = readOnly,
            label = { Text(label) },
            suffix = { Text(suffix) },
            supportingText = if (isError) {
                { InlineError(text = error) }
            } else {
                null
            },
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            singleLine = true,
            shape = KryptShape.md,
        )
    }
}

/**
 * SummarySentence: the choice read back in one serif sentence, e.g.
 * "Instagram, *30 minutes*, once." The duration is in italic; an app that
 * isn't installed here is named by its package, in mono.
 */
@Composable
private fun SummarySentence(choice: AccessChoice, appLabel: String?, packageName: String) {
    val app = if (appLabel != null) {
        StyledArg(appLabel)
    } else {
        StyledArg(packageName, SpanStyle(fontFamily = GeistMono, fontSize = SENTENCE_MONO_SIZE))
    }
    val italic = SpanStyle(fontStyle = FontStyle.Italic)
    val sentence = when (choice) {
        is AccessChoice.OneTime -> styledFormat(
            stringResource(R.string.guardian_summary_once),
            app,
            StyledArg(durationWords(choice.minutes), italic),
        )
        is AccessChoice.EveryDay -> styledFormat(
            stringResource(R.string.guardian_summary_daily),
            app,
            StyledArg(durationWords(choice.minutes), italic),
            StyledArg(daysWords(choice.days)),
        )
        AccessChoice.ForGood -> styledFormat(
            stringResource(R.string.guardian_summary_for_good),
            app,
            StyledArg(stringResource(R.string.access_for_good), italic),
        )
    }
    Text(
        text = sentence,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = KryptTheme.text.headline,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The thin progress line while the link is read. */
@Composable
private fun ProgressLine() {
    Spacer(Modifier.height(KryptSpace.s6))
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
    )
}

/** The app's name on this phone, or null when it isn't installed here. */
@Composable
private fun rememberAppLabel(packageName: String?): String? {
    val context = LocalContext.current
    return remember(packageName) {
        packageName?.let { pkg ->
            runCatching {
                val pm = context.packageManager
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }
    }
}

/**
 * The form to show if the screen was recreated while a PIN was being
 * checked (nothing chosen is known then). The ViewModel already holds what
 * is being approved; this is only drawn until its next state.
 */
private fun GuardianPinState.Validating.placeholderForm() = GuardianPinState.Ready(
    request = request,
    attemptsLeft = 0,
    lockoutRetryInMs = null,
    errorMessage = null,
    form = AccessForm(everyDayAllowed = request.supportsDaily, forGoodAllowed = request.supportsForGood),
)

@Composable
private fun fatalMessage(key: String): String = when (key) {
    "request_expired" -> stringResource(R.string.guardian_error_expired)
    "request_corrupt" -> stringResource(R.string.guardian_error_corrupt)
    else -> stringResource(R.string.guardian_error_unreadable)
}

@Composable
private fun kindLabel(kind: AccessForm.Kind): String = when (kind) {
    AccessForm.Kind.ONE_TIME -> stringResource(R.string.guardian_once)
    AccessForm.Kind.EVERY_DAY -> stringResource(R.string.guardian_daily)
    AccessForm.Kind.FOR_GOOD -> stringResource(R.string.guardian_for_good)
}

/** A duration chip: "15 min", "30 min", "1 hour", "2 hours". */
@Composable
private fun minutesChipLabel(minutes: Int): String =
    if (minutes % 60 == 0) {
        pluralStringResource(R.plurals.duration_hours, minutes / 60, minutes / 60)
    } else {
        pluralStringResource(R.plurals.chip_minutes, minutes, minutes)
    }

/** A duration in a sentence: "30 minutes", "1 hour". */
@Composable
private fun durationWords(minutes: Int): String =
    if (minutes % 60 == 0) {
        pluralStringResource(R.plurals.duration_hours, minutes / 60, minutes / 60)
    } else {
        pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    }

/** "7 days". */
@Composable
private fun daysWords(days: Int): String = pluralStringResource(R.plurals.duration_days, days, days)

/** What an approval allows, in the reply: "30 minutes, once" or "1 hour a day for 7 days". */
@Composable
private fun accessText(access: AccessChoice): String = when (access) {
    is AccessChoice.OneTime -> stringResource(R.string.access_once, durationWords(access.minutes))
    is AccessChoice.EveryDay -> stringResource(
        R.string.access_daily,
        durationWords(access.minutes),
        daysWords(access.days),
    )
    AccessChoice.ForGood -> stringResource(R.string.access_for_good)
}

/** Time left as a ticking clock, e.g. "4:00". */
private fun clockText(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private const val PIN_MIN_LENGTH = 4
private const val PIN_MAX_LENGTH = 8
private const val MINUTES_MAX_DIGITS = 4
private const val DAYS_MAX_DIGITS = 3
private const val TICK_MS = 1_000L

/** Stands in for each digit while it is checked; PinEntry draws only dots, never characters. */
private const val CHECKING_DOT = "\u2022"

/** The package in mono inside the summary sentence (SummarySentence preview). */
private val SENTENCE_MONO_SIZE = 18.sp


/** Revealing a field: `motion-base`. */
private val Reveal: EnterTransition =
    expandVertically(
        animationSpec = tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing),
        expandFrom = Alignment.Top,
    ) + fadeIn(animationSpec = tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing))

private val Conceal: ExitTransition =
    shrinkVertically(
        animationSpec = tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing),
        shrinkTowards = Alignment.Top,
    ) + fadeOut(animationSpec = tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing))
