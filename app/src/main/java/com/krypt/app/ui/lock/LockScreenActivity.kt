package com.krypt.app.ui.lock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.krypt.app.R
import com.krypt.app.data.LockReason
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.ui.components.AppIcon
import com.krypt.app.ui.components.BodyText
import com.krypt.app.ui.components.Eyebrow
import com.krypt.app.ui.components.InlineError
import com.krypt.app.ui.components.KryptIcon
import com.krypt.app.ui.components.KryptIcons
import com.krypt.app.ui.components.KryptPage
import com.krypt.app.ui.components.PrimaryButton
import com.krypt.app.ui.components.Statement
import com.krypt.app.ui.components.StyledArg
import com.krypt.app.ui.components.TertiaryButton
import com.krypt.app.ui.components.styledFormat
import com.krypt.app.ui.home.AppIconCache
import com.krypt.app.ui.link.pasteKryptLinkAction
import com.krypt.app.ui.link.shareTextChooser
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Amendment 2 lock screen. [com.krypt.app.service.AppLockerAccessibilityService]
 * starts it the moment a locked app reaches the foreground, sending that
 * app behind the home screen at the same time, so the app itself is never
 * usable.
 *
 * Nothing here leads back into the locked app: Back and "Back to home" both
 * go to the launcher, and there is no PIN entry here, ever (FR-018). The only
 * way in is the Guardian's approval - "Request access" shares a
 * `krypt://request` URL, and once the Guardian's reply is consumed (its link
 * tapped, or copied and pasted with "Paste reply") the recorded grant closes
 * this screen and [com.krypt.app.subject.ApprovalTrampolineActivity] opens
 * the app.
 *
 * Runs in its own task and is excluded from Recents (see the manifest). The
 * window background (Theme.Krypt.LockScreen) is the page's own `background`,
 * so the first frame already hides the app and matches the page before
 * Compose draws. An [AppCompatActivity] so the Appearance setting's night
 * mode applies to it on every Android version.
 */
@AndroidEntryPoint
class LockScreenActivity : AppCompatActivity() {

    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var iconCache: AppIconCache

    private val viewModel: LockScreenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val lockedPackage = intent.lockedPackage() ?: run {
            finish()
            return
        }
        viewModel.show(lockedPackage)

        onBackPressedDispatcher.addCallback(this) { goHome() }

        lifecycleScope.launch {
            sessionStore.grantEvents.collect { granted ->
                if (granted == viewModel.state.value.lockedPackage) finish()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.shareRequests.collect(::shareWithGuardian)
            }
        }
        lifecycleScope.launch {
            viewModel.closeRequests.collect { finish() }
        }

        setContent {
            KryptTheme {
                LockScreen(viewModel = viewModel, iconCache = iconCache, onGoHome = ::goHome)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.lockedPackage()?.let(viewModel::show)
    }

    private fun shareWithGuardian(requestUrl: String) {
        val lockedPackage = viewModel.state.value.lockedPackage ?: return
        val message = getString(R.string.request_share_text, appLabel(this, lockedPackage), requestUrl)
        try {
            startActivity(shareTextChooser(this, message, getString(R.string.lock_share_title)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no share sheet to send the request with", e)
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        private const val TAG = "KryptLockScreen"
        private const val EXTRA_LOCKED_PACKAGE = "com.krypt.app.extra.LOCKED_PACKAGE"

        fun intentFor(context: Context, lockedPackage: String): Intent =
            Intent(context, LockScreenActivity::class.java)
                .putExtra(EXTRA_LOCKED_PACKAGE, lockedPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun Intent.lockedPackage(): String? = getStringExtra(EXTRA_LOCKED_PACKAGE)
    }
}

/**
 * The page (docs/design-system/components/LockScreen): the app, why it's
 * blocked, the request hand-off, and the actions pinned to the bottom.
 * KryptPage draws the theme's `background` behind all of it, the same colour
 * as the window's first frame.
 */
@Composable
private fun LockScreen(
    viewModel: LockScreenViewModel,
    iconCache: AppIconCache,
    onGoHome: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lockedPackage = state.lockedPackage ?: return
    val appLabel = rememberAppLabel(lockedPackage)
    // For messengers that don't make krypt:// links tappable. Starts clear for
    // each app, and clears again with each new request.
    var noReplyOnClipboard by rememberSaveable(lockedPackage) { mutableStateOf(false) }
    val pasteReply = pasteKryptLinkAction(approvalsOnly = true) { noReplyOnClipboard = true }
    val sentAt = rememberSentAt(lockedPackage, state.request)

    KryptPage(
        topPadding = KryptSpace.s12,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.lock_request_access),
                onClick = {
                    noReplyOnClipboard = false
                    viewModel.askGuardian()
                },
                busy = state.request == RequestStatus.Preparing,
                busyText = stringResource(R.string.lock_preparing),
                icon = KryptIcons.Share,
            )
            if (state.request == RequestStatus.None) {
                // The only centred text on the page: the hint under its button.
                Text(
                    text = stringResource(R.string.lock_request_hint),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            ActionPair {
                // TertiaryButton pads its label by s3; pull each one out by that
                // much so both labels sit on the gutter, as in the design.
                TertiaryButton(
                    text = stringResource(R.string.lock_paste_reply),
                    onClick = pasteReply,
                    modifier = Modifier.offset(x = -KryptSpace.s3),
                    icon = KryptIcons.ContentPaste,
                )
                TertiaryButton(
                    text = stringResource(R.string.lock_back_home),
                    onClick = onGoHome,
                    modifier = Modifier.offset(x = KryptSpace.s3),
                    icon = KryptIcons.Home,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(KryptSpace.s6),
        ) {
            Eyebrow(stringResource(R.string.lock_eyebrow))
            LockedApp(lockedPackage, appLabel, iconCache)
            Reason(state.reason, appLabel, Modifier.padding(top = KryptSpace.s6))
            RequestHandoff(state.request, sentAt, appLabel)
            if (noReplyOnClipboard) InlineError(stringResource(R.string.lock_paste_no_reply))
        }
    }
}

/** The locked app: its icon tile, its name, and its package in mono. */
@Composable
private fun LockedApp(packageName: String, label: String, iconCache: AppIconCache) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s3),
    ) {
        AppIcon(
            packageName = packageName,
            label = label,
            iconCache = iconCache,
            size = KryptSize.appIconTile,
            shape = KryptShape.lg,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(text = packageName, style = KryptTheme.text.monoPackage, color = colors.onSurfaceVariant)
        }
    }
}

/**
 * Why the app is blocked: an icon in the colour of its meaning, the one-word
 * statement and its line. Recomposes in place when the reason changes while
 * the screen is up.
 */
@Composable
private fun Reason(reason: LockReason, appLabel: String, modifier: Modifier = Modifier) {
    val caution = KryptTheme.colors.caution
    val (icon, tint) = when (reason) {
        LockReason.Locked -> KryptIcons.Lock to MaterialTheme.colorScheme.onSurface
        is LockReason.DailyUsedUp -> KryptIcons.HourglassBottom to caution
        is LockReason.DailyPaused -> KryptIcons.Schedule to caution
    }
    val title = when (reason) {
        LockReason.Locked -> stringResource(R.string.lock_title)
        is LockReason.DailyUsedUp -> stringResource(R.string.lock_title_daily_used_up)
        is LockReason.DailyPaused -> stringResource(R.string.lock_title_daily_paused)
    }
    val body = when (reason) {
        LockReason.Locked -> stringResource(R.string.lock_body, appLabel)
        is LockReason.DailyUsedUp ->
            pluralStringResource(R.plurals.lock_body_daily_used_up, reason.minutesPerDay, reason.minutesPerDay)
        is LockReason.DailyPaused -> stringResource(R.string.lock_body_daily_paused)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KryptSpace.s2)) {
        // Decorative: the statement under it says the same in words.
        KryptIcon(icon = icon, contentDescription = null, tint = tint, size = KryptSize.iconLg)
        Statement(title)
        BodyText(body)
    }
}

/**
 * What follows Request access (docs/design-system/components/ShareHandoff):
 * nothing before it or while it is prepared, the three-step timeline once it
 * is sent (until it expires, [sentAt] plus 5 minutes), or one error line,
 * after which the button works again.
 */
@Composable
private fun RequestHandoff(request: RequestStatus, sentAt: Long?, appLabel: String) {
    when (request) {
        RequestStatus.None, RequestStatus.Preparing -> Unit
        RequestStatus.Sent -> if (sentAt != null) {
            val secondsLeft = rememberSecondsLeft(sentAt)
            if (secondsLeft > 0) {
                SentTimeline(
                    appLabel = appLabel,
                    timeLeft = timeLeftText(secondsLeft),
                    modifier = Modifier.padding(top = KryptSpace.s2),
                )
            } else {
                InlineError(stringResource(R.string.lock_request_expired))
            }
        }
        RequestStatus.NotConfigured -> InlineError(stringResource(R.string.lock_status_not_configured))
        RequestStatus.Failed -> InlineError(stringResource(R.string.lock_status_failed))
    }
}

/**
 * When the current request was sent (elapsed realtime), or null while none
 * is: the request's 5-minute lifetime is counted in the UI from the moment it
 * shows as sent. Saved across rotation. Any other status clears it, so each
 * new send (which passes through Preparing) starts a fresh clock, and a time
 * restored after the process died, when the ViewModel starts again at None,
 * is dropped rather than reused by the next request.
 */
@Composable
private fun rememberSentAt(lockedPackage: String, request: RequestStatus): Long? {
    var saved by rememberSaveable(lockedPackage) { mutableStateOf<Long?>(null) }
    val sentAt = if (request == RequestStatus.Sent) saved ?: SystemClock.elapsedRealtime() else null
    SideEffect { saved = sentAt }
    return sentAt
}

/**
 * The hand-off once sent: three steps joined by a 1dp line. A done step has a
 * filled dot, the current one a ring in ink, the next one a ring in outline.
 * The time left ticks in mono.
 */
@Composable
private fun SentTimeline(appLabel: String, timeLeft: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyMedium
    val mono = SpanStyle(fontFamily = KryptTheme.text.monoTime.fontFamily)
    // Centre each dot on its step's first line, at any font scale.
    val lineHeight = body.lineHeight
    val dotTop = if (lineHeight.isSp) {
        with(LocalDensity.current) { maxOf((lineHeight.toDp() - StepDotSize) / 2, 0.dp) }
    } else {
        0.dp
    }
    Column(
        // Announced when it appears. The ticking time below is an ordinary Text,
        // not a live region of its own, so it isn't read out every second.
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        TimelineStep(StepState.Done, dotTop) {
            Text(
                text = stringResource(R.string.lock_step_sent),
                style = body,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TimelineStep(StepState.Current, dotTop) {
            Text(
                text = stringResource(R.string.lock_step_waiting),
                style = body,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = styledFormat(stringResource(R.string.lock_step_expires), StyledArg(timeLeft, mono)),
                style = body,
                color = colors.onSurfaceVariant,
            )
        }
        TimelineStep(StepState.Upcoming, dotTop, last = true) {
            Text(
                text = stringResource(R.string.lock_step_open, appLabel),
                style = body,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private enum class StepState { Done, Current, Upcoming }

/**
 * One step: its dot, [dotTop] down so it sits on the first line of text, and
 * unless it is the [last] step, the line from under the dot to the next one.
 * The gap to the next step is part of this row, so the line runs through it.
 */
@Composable
private fun TimelineStep(
    state: StepState,
    dotTop: Dp,
    last: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s3),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(top = dotTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepDot(state)
            if (!last) {
                Box(
                    modifier = Modifier
                        .padding(top = KryptSpace.s1)
                        .weight(1f)
                        .width(KryptSize.hairline)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (last) 0.dp else KryptSpace.s4),
            content = content,
        )
    }
}

@Composable
private fun StepDot(state: StepState) {
    val colors = MaterialTheme.colorScheme
    val look = when (state) {
        StepState.Done -> Modifier.background(colors.primary, CircleShape)
        StepState.Current -> Modifier.border(StepRingCurrent, colors.primary, CircleShape)
        StepState.Upcoming -> Modifier.border(StepRingUpcoming, colors.outline, CircleShape)
    }
    Box(modifier = Modifier.size(StepDotSize).then(look))
}

/** The timeline's dots (`radius-full`) and the widths of their rings. */
private val StepDotSize = 10.dp
private val StepRingCurrent = 2.dp
private val StepRingUpcoming = 1.5.dp

/**
 * Two actions at either end of one line, placed as Row(SpaceBetween) places
 * them while both fit. When they don't (a narrow phone, large text), each
 * gets half the line and its label wraps, instead of the second one being
 * squeezed to nothing.
 */
@Composable
private fun ActionPair(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val natural = measurables.sumOf { it.maxIntrinsicWidth(constraints.maxHeight) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else natural
        val share = width / measurables.size.coerceAtLeast(1)
        var free = width
        val placeables = measurables.map { measurable ->
            val maxWidth = if (natural <= width) free else share
            measurable.measure(Constraints(maxWidth = maxWidth.coerceAtLeast(0)))
                .also { free -= it.width }
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val x = if (index == 0) 0 else width - placeable.width
                placeable.placeRelative(x, (height - placeable.height) / 2)
            }
        }
    }
}

/**
 * Whole seconds until a request sent at [sentAt] (elapsed realtime) expires,
 * recounted from the clock each second.
 */
@Composable
private fun rememberSecondsLeft(sentAt: Long): Long {
    var secondsLeft by remember(sentAt) { mutableLongStateOf(secondsLeftAt(sentAt)) }
    LaunchedEffect(sentAt) {
        while (secondsLeft > 0) {
            // Wake just past the next whole second, so the count neither drifts nor skips.
            delay(msLeftAt(sentAt) % 1_000 + 1)
            secondsLeft = secondsLeftAt(sentAt)
        }
    }
    return secondsLeft
}

/** A request's lifetime ([UnlockRequest.DEFAULT_TTL_SECONDS], 5 minutes). */
private const val REQUEST_TTL_MS = UnlockRequest.DEFAULT_TTL_SECONDS * 1_000

private fun msLeftAt(sentAt: Long): Long = sentAt + REQUEST_TTL_MS - SystemClock.elapsedRealtime()

/** Rounded down, so the count starts at 4:59 and the request has expired when it reads 0:00. */
private fun secondsLeftAt(sentAt: Long): Long =
    (msLeftAt(sentAt) / 1_000).coerceIn(0L, UnlockRequest.DEFAULT_TTL_SECONDS)

/** "4:59", written as the lockout countdown writes its time (no locale digits). */
private fun timeLeftText(seconds: Long): String =
    "${seconds / 60}:" + (seconds % 60).toString().padStart(2, '0')

@Composable
private fun rememberAppLabel(pkg: String): String {
    val context = LocalContext.current
    return remember(pkg) { appLabel(context, pkg) }
}

private fun appLabel(context: Context, pkg: String): String {
    val pm = context.packageManager
    return runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
}
