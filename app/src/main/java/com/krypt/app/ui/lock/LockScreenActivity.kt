package com.krypt.app.ui.lock

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.krypt.app.R
import com.krypt.app.data.LockReason
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.ui.home.AppIconCache
import com.krypt.app.ui.link.pasteKryptLinkAction
import com.krypt.app.ui.link.shareTextChooser
import com.krypt.app.ui.theme.KryptAmber
import com.krypt.app.ui.theme.KryptPurple
import com.krypt.app.ui.theme.KryptTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Amendment 2 lock screen. [com.krypt.app.service.AppLockerAccessibilityService]
 * starts it the moment a locked app reaches the foreground, sending that
 * app behind the home screen at the same time, so the app itself is never
 * usable.
 *
 * Nothing here leads back into the locked app: Back and "Go to home screen"
 * both go to the launcher. The only way in is Guardian approval - "Ask
 * Guardian" shares a `krypt://request` URL, and once the Guardian's approval
 * link is consumed (tapped, or copied and pasted with "Paste approval link")
 * the recorded grant closes this screen and
 * [com.krypt.app.subject.ApprovalTrampolineActivity] opens the app.
 *
 * Runs in its own task and is excluded from Recents (see the manifest). The
 * theme's window background is the lock screen's gradient, so the first
 * frame already hides the app before Compose draws.
 */
@AndroidEntryPoint
class LockScreenActivity : ComponentActivity() {

    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var iconCache: AppIconCache

    private val viewModel: LockScreenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
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
        startActivity(shareTextChooser(this, message, getString(R.string.home_share_chooser_title)))
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
        private const val EXTRA_LOCKED_PACKAGE = "com.krypt.app.extra.LOCKED_PACKAGE"

        fun intentFor(context: Context, lockedPackage: String): Intent =
            Intent(context, LockScreenActivity::class.java)
                .putExtra(EXTRA_LOCKED_PACKAGE, lockedPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun Intent.lockedPackage(): String? = getStringExtra(EXTRA_LOCKED_PACKAGE)
    }
}

@Composable
private fun LockScreen(
    viewModel: LockScreenViewModel,
    iconCache: AppIconCache,
    onGoHome: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lockedPackage = state.lockedPackage ?: return
    val appLabel = rememberAppLabel(lockedPackage)
    var icon by remember(lockedPackage) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(lockedPackage) { icon = iconCache.loadAsync(lockedPackage) }

    // No background here: the window background (Theme.Krypt.LockScreen)
    // draws the gradient, so the starting window and this content match.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.lock_icon_desc),
                    modifier = Modifier.size(96.dp),
                )
            }
            Text(
                text = appLabel,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = lockedPackage,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.7f),
            )
            val (title, body) = reasonText(state.reason)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = viewModel::askGuardian,
                enabled = state.request != RequestStatus.Preparing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = KryptAmber,
                    contentColor = KryptPurple,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(56.dp),
            ) { Text(stringResource(R.string.lock_ask_guardian)) }
            requestStatusText(state.request, appLabel)?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
            // For messaging apps that don't make krypt:// links tappable.
            var noApprovalOnClipboard by remember(lockedPackage) { mutableStateOf(false) }
            val pasteApproval = pasteKryptLinkAction(approvalsOnly = true) {
                noApprovalOnClipboard = true
            }
            TextButton(onClick = pasteApproval) {
                Text(
                    text = stringResource(R.string.lock_paste_approval),
                    color = KryptAmber,
                )
            }
            if (noApprovalOnClipboard) {
                Text(
                    text = stringResource(R.string.lock_paste_no_approval),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(onClick = onGoHome) {
                Text(
                    text = stringResource(R.string.lock_go_home),
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
        Text(
            text = stringResource(R.string.lock_footer),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Title and body for why the app is blocked. */
@Composable
private fun reasonText(reason: LockReason): Pair<String, String> = when (reason) {
    LockReason.Locked -> stringResource(R.string.lock_title) to stringResource(R.string.lock_body)
    is LockReason.DailyUsedUp -> stringResource(R.string.lock_title_daily_used_up) to
        pluralStringResource(R.plurals.lock_body_daily_used_up, reason.minutesPerDay, reason.minutesPerDay)
    is LockReason.DailyPaused -> stringResource(R.string.lock_title_daily_paused) to
        stringResource(R.string.lock_body_daily_paused)
}

@Composable
private fun requestStatusText(status: RequestStatus, appLabel: String): String? = when (status) {
    RequestStatus.None -> null
    RequestStatus.Preparing -> stringResource(R.string.lock_status_preparing)
    RequestStatus.Sent -> {
        val minutes = (UnlockRequest.DEFAULT_TTL_SECONDS / 60).toInt()
        pluralStringResource(R.plurals.lock_status_sent, minutes, appLabel, minutes)
    }
    RequestStatus.NotConfigured -> stringResource(R.string.lock_status_not_configured)
    RequestStatus.Failed -> stringResource(R.string.lock_status_failed)
}

@Composable
private fun rememberAppLabel(pkg: String): String {
    val context = LocalContext.current
    return remember(pkg) { appLabel(context, pkg) }
}

private fun appLabel(context: Context, pkg: String): String {
    val pm = context.packageManager
    return runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
}
