package com.krypt.app.ui.main

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.permission.PermissionClassification
import com.krypt.app.permission.PermissionIndicator
import com.krypt.app.permission.PermissionKey
import com.krypt.app.service.KryptDeviceAdminReceiver
import com.krypt.app.ui.components.BodyText
import com.krypt.app.ui.components.Eyebrow
import com.krypt.app.ui.components.GroupDivider
import com.krypt.app.ui.components.InlineError
import com.krypt.app.ui.components.KryptGroup
import com.krypt.app.ui.components.KryptIcon
import com.krypt.app.ui.components.KryptIcons
import com.krypt.app.ui.components.KryptPage
import com.krypt.app.ui.components.PrimaryButton
import com.krypt.app.ui.components.ScreenTitle
import com.krypt.app.ui.components.SectionHeader
import com.krypt.app.ui.components.SmallButton
import com.krypt.app.ui.components.TertiaryButton
import com.krypt.app.ui.link.pasteKryptLinkAction
import com.krypt.app.ui.onboarding.OnboardingStep
import com.krypt.app.ui.onboarding.OnboardingViewModel
import com.krypt.app.ui.theme.KryptMotion
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme
import kotlinx.coroutines.launch

/**
 * Onboarding as one checklist (docs/design-system/Screens.md, "Onboarding:
 * one checklist"): the three required permissions, then the two optional
 * ones. Each row shows its live state and, until it is allowed, a button to
 * the page that allows it. The states refresh on resume, so a row changes
 * as soon as the person comes back from Settings.
 *
 * Start protecting stays disabled until all three required items are
 * allowed. Optional rows never block anything and have no Skip: leaving one
 * alone is the skip. Paste a link sits in the top bar for a Guardian-only
 * phone that came this way anyway.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkNotFound = stringResource(R.string.link_not_found)
    val pasteLink = pasteKryptLinkAction {
        scope.launch { snackbarHostState.showSnackbar(linkNotFound) }
    }

    // One launcher per kind of hand-off, registered once for every row.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }
    // The row whose Settings page this phone couldn't open; it says so inline.
    var unopenable by remember { mutableStateOf<OnboardingStep?>(null) }
    var promptedAt by remember { mutableLongStateOf(0L) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // An answer faster than anyone can tap means no prompt appeared: the
        // permission was turned down for good, and only Settings can allow it.
        if (!granted && SystemClock.elapsedRealtime() - promptedAt < NO_PROMPT_MS) {
            if (!settingsLauncher.launchSafely(appNotificationSettings(context))) {
                unopenable = OnboardingStep.Notifications
            }
        }
    }
    val onAllow: (OnboardingStep) -> Unit = { step ->
        unopenable = null
        val pages = settingsPages(context, step)
        val opened = when {
            // The first page this phone has; the rest are fallbacks.
            pages.isNotEmpty() -> pages.any { settingsLauncher.launchSafely(it) }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                promptedAt = SystemClock.elapsedRealtime()
                permissionLauncher.launchSafely(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> false
        }
        if (!opened) unopenable = step
    }
    val oemGuide = remember { OemBatteryLinks.guideForCurrentOem() }
    val onBatteryHelp: (() -> Unit)? = oemGuide?.let { guide ->
        {
            unopenable = null
            if (!openBatteryHelp(context, guide, settingsLauncher)) unopenable = OnboardingStep.Battery
        }
    }

    DisposableEffect(lifecycleOwner) {
        val unbind = viewModel.permissionState.bind(lifecycleOwner)
        onDispose { unbind() }
    }

    val required = remember {
        OnboardingStep.ORDERED.filter { it.classification == PermissionClassification.MANDATORY }
    }
    val optional = remember {
        OnboardingStep.ORDERED.filter { it.classification == PermissionClassification.OPTIONAL }
    }

    KryptPage(
        snackbarHostState = snackbarHostState,
        // The top row is as tall as its 48dp button, with the eyebrow centred
        // in it: start higher so the eyebrow sits where it does on every screen.
        topPadding = KryptSpace.s8,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.onboarding_start),
                onClick = { viewModel.tryFinish(onComplete) },
                enabled = state.canFinish,
            )
            if (!state.canFinish) {
                Text(
                    text = stringResource(R.string.onboarding_start_hint, state.mandatoryTotal),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            TertiaryButton(text = stringResource(R.string.action_back), onClick = onBack)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(
                text = stringResource(R.string.eyebrow_krypt),
                modifier = Modifier.weight(1f),
            )
            // Pulled into the gutter so its text ends on the column's edge.
            TertiaryButton(
                text = stringResource(R.string.paste_link),
                onClick = pasteLink,
                modifier = Modifier.offset(x = KryptSpace.s3),
                icon = KryptIcons.Link,
            )
        }
        ScreenTitle(stringResource(R.string.onboarding_title))
        Spacer(Modifier.height(KryptSpace.s2))
        BodyText(stringResource(R.string.onboarding_body))
        Spacer(Modifier.height(KryptSpace.s6))
        MandatoryProgress(granted = state.mandatoryGranted, total = state.mandatoryTotal)

        SectionHeader(stringResource(R.string.onboarding_required))
        ChecklistGroup(
            steps = required,
            permissions = state.permissions,
            unopenable = unopenable,
            onAllow = onAllow,
            onBatteryHelp = onBatteryHelp,
        )
        SectionHeader(stringResource(R.string.onboarding_optional))
        ChecklistGroup(
            steps = optional,
            permissions = state.permissions,
            unopenable = unopenable,
            onAllow = onAllow,
            onBatteryHelp = onBatteryHelp,
        )
    }
}

/**
 * Three thin ticks, one per required permission, over the line "2 of 3
 * required" (docs/design-system/components/MandatoryProgress). Optional
 * permissions never move them. For TalkBack the ticks are one progress bar
 * that reads the same line; the line stays its own text.
 */
@Composable
private fun MandatoryProgress(granted: Int, total: Int) {
    val colors = MaterialTheme.colorScheme
    val ready = total > 0 && granted >= total
    val line = if (ready) {
        stringResource(R.string.onboarding_progress_ready, granted, total)
    } else {
        stringResource(R.string.onboarding_progress, granted, total)
    }
    Column(verticalArrangement = Arrangement.spacedBy(KryptSpace.s2)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(granted.toFloat(), 0f..total.toFloat())
                    stateDescription = line
                },
            horizontalArrangement = Arrangement.spacedBy(TICK_GAP),
        ) {
            repeat(total) { index ->
                val tick by animateColorAsState(
                    targetValue = if (index < granted) colors.primary else colors.outlineVariant,
                    animationSpec = tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing),
                    label = "tick",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(TICK_HEIGHT)
                        .background(tick, CircleShape),
                )
            }
        }
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = if (ready) KryptTheme.colors.granted else colors.onSurfaceVariant,
        )
    }
}

/** One grouped section of the checklist, its rows divided by hairlines. */
@Composable
private fun ChecklistGroup(
    steps: List<OnboardingStep>,
    permissions: Map<PermissionKey, Boolean>,
    unopenable: OnboardingStep?,
    onAllow: (OnboardingStep) -> Unit,
    onBatteryHelp: (() -> Unit)?,
) {
    KryptGroup {
        steps.forEachIndexed { index, step ->
            if (index > 0) GroupDivider()
            ChecklistRow(
                step = step,
                granted = permissions[step.key] == true,
                pageMissing = step == unopenable,
                onAllow = { onAllow(step) },
                onHelp = if (step == OnboardingStep.Battery) onBatteryHelp else null,
            )
        }
    }
}

/**
 * One permission (docs/design-system/components/PermissionStep): its icon,
 * a plain-words title and its live state; until it is allowed, also what it
 * is for and a button to the page that allows it.
 */
@Composable
private fun ChecklistRow(
    step: OnboardingStep,
    granted: Boolean,
    pageMissing: Boolean,
    onAllow: () -> Unit,
    onHelp: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(KryptSpace.s4),
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s3),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(KryptSize.appIconRow)
                .background(colors.secondaryContainer, KryptShape.sm),
            contentAlignment = Alignment.Center,
        ) {
            KryptIcon(
                icon = step.icon,
                contentDescription = null,
                tint = colors.onSecondaryContainer,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ROW_BODY_GAP),
        ) {
            Text(
                text = stringResource(step.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            AnimatedVisibility(visible = !granted, enter = REVEAL, exit = CONCEAL) {
                Text(
                    text = stringResource(step.bodyRes),
                    modifier = Modifier.padding(bottom = KryptSpace.s1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            PermissionIndicator(
                granted = granted,
                optional = step.classification == PermissionClassification.OPTIONAL,
            )
            AnimatedVisibility(visible = !granted, enter = REVEAL, exit = CONCEAL) {
                SmallButton(
                    text = stringResource(step.grantRes),
                    onClick = onAllow,
                    modifier = Modifier.padding(top = KryptSpace.s3),
                    // Notifications is a system prompt, not a trip to Settings.
                    trailingIcon = if (step == OnboardingStep.Notifications) null else KryptIcons.OpenInNew,
                )
            }
            if (pageMissing && !granted) {
                InlineError(
                    text = stringResource(R.string.onboarding_settings_missing),
                    modifier = Modifier.padding(top = KryptSpace.s2),
                )
            }
            if (onHelp != null) {
                // Pulled to the start so its text lines up with the title.
                TertiaryButton(
                    text = stringResource(R.string.onboarding_battery_help),
                    onClick = onHelp,
                    modifier = Modifier.offset(x = -KryptSpace.s3),
                )
            }
        }
    }
}

/** The step's icon (docs/design-system/components/PermissionStep). */
@get:DrawableRes
private val OnboardingStep.icon: Int
    get() = when (this) {
        OnboardingStep.Accessibility -> KryptIcons.AccessibilityNew
        OnboardingStep.Overlay -> KryptIcons.Layers
        OnboardingStep.Battery -> KryptIcons.BatteryAlert
        OnboardingStep.DeviceAdmin -> KryptIcons.AdminPanelSettings
        OnboardingStep.Notifications -> KryptIcons.Notifications
    }

/**
 * The Settings pages that allow [step], best first: some phones lack the
 * exact page, so the later ones are fallbacks. Empty for Notifications on
 * Android 13 and newer, where it is a system prompt; before that it is a
 * switch in the app's notification settings.
 */
private fun settingsPages(context: Context, step: OnboardingStep): List<Intent> {
    val packageUri = Uri.parse("package:${context.packageName}")
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    return when (step) {
        OnboardingStep.Accessibility -> listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        OnboardingStep.Overlay -> listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            appDetails,
        )
        OnboardingStep.Battery -> listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails,
        )
        OnboardingStep.DeviceAdmin -> listOf(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(context, KryptDeviceAdminReceiver::class.java),
                )
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.device_admin_explanation),
                ),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        OnboardingStep.Notifications ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                emptyList()
            } else {
                listOf(appNotificationSettings(context), appDetails)
            }
    }
}

/** Krypt's page in the system's notification settings. */
private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * Opens the phone maker's own battery or autostart page. When it is missing
 * or won't open for Krypt, opens Android's battery optimisation list instead.
 * Returns false when neither opened.
 */
private fun openBatteryHelp(
    context: Context,
    guide: OemBatteryLinks.OemGuide,
    settingsLauncher: ActivityResultLauncher<Intent>,
): Boolean {
    val opened = try {
        OemBatteryLinks.tryLaunch(context, guide)
    } catch (e: SecurityException) {
        Log.w(TAG, "${guide.label} won't open for Krypt", e)
        false
    }
    return opened || settingsLauncher.launchSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

/**
 * Starts [input] (a Settings page or a permission prompt) and returns
 * whether it opened. A phone that lacks the page, or won't open it for
 * Krypt, gets false instead of a crash, so the caller can try another page
 * or say so.
 */
private fun <I> ActivityResultLauncher<I>.launchSafely(input: I): Boolean = try {
    launch(input)
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "Nothing on this phone opens $input", e)
    false
} catch (e: SecurityException) {
    Log.w(TAG, "$input won't open for Krypt", e)
    false
}

/** Revealing or hiding a row's explanation and button, over `motion-base`. */
private val REVEAL = fadeIn(tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing)) +
    expandVertically(tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing))
private val CONCEAL = fadeOut(tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing)) +
    shrinkVertically(tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing))

/** The progress ticks (`.kx-ticks` / `.kx-tick`). */
private val TICK_HEIGHT = 3.dp
private val TICK_GAP = 6.dp

/** Between a row's title, explanation and state (`.kx-row__body`). */
private val ROW_BODY_GAP = 2.dp

/** Faster than a person can answer the notification prompt. */
private const val NO_PROMPT_MS = 600L

private const val TAG = "KryptOnboarding"
