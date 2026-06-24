package com.krypt.app.ui.guardian

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.krypt.app.deeplink.DeepLinkScheme
import com.krypt.app.guardian.GuardianPinScreen
import com.krypt.app.ui.pairing.GuardianPairConsumeScreen
import com.krypt.app.ui.pairing.SubjectPairedConsumeScreen
import com.krypt.app.ui.theme.KryptTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Deep-link host for every krypt://{authority} URL. Self-whitelisted by
 * [com.krypt.app.service.AppLockerAccessibilityService] so Guardians can
 * complete approvals on devices where Krypt also protects apps (FR-011).
 *
 * Amendment 1 routing:
 *   - `krypt://request?...` -> WP21 Guardian PIN screen (stub below until WP21 lands)
 *   - `krypt://approve?...` -> WP22 silent ApprovalTrampolineActivity (handled
 *     by that Activity's own intent-filter, not routed through here)
 *   - `krypt://pair?...` / `krypt://paired?...` -> legacy pairing screens
 *     (shipped code, unreachable from live nav after Amendment 1 but still
 *     deep-link-openable if a sender crafts one).
 */
@AndroidEntryPoint
class GuardianActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent { KryptTheme { GuardianRoute(intent.data) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { KryptTheme { GuardianRoute(intent.data) } }
    }
}

@Composable
fun GuardianRoute(data: Uri?) {
    if (data == null || data.scheme != DeepLinkScheme.SCHEME) {
        UnknownLink()
        return
    }
    val authority = data.authority
    val url = data.toString()
    when (authority) {
        DeepLinkScheme.AUTHORITY_REQUEST -> GuardianPinScreen(incomingUrl = url)
        // krypt://approve is owned by ApprovalTrampolineActivity (WP22).
        // If we see it here, Android's resolver fell through - treat as
        // unknown so the user isn't silently stuck.
        DeepLinkScheme.AUTHORITY_APPROVE -> UnknownLink()
        DeepLinkScheme.AUTHORITY_PAIR -> GuardianPairConsumeScreen(incomingUrl = url, onDone = {})
        DeepLinkScheme.AUTHORITY_PAIRED -> SubjectPairedConsumeScreen(incomingUrl = url, onDone = {})
        else -> UnknownLink()
    }
}

@Composable
private fun UnknownLink() {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Unknown Krypt deep link", style = MaterialTheme.typography.headlineSmall)
        Text("Open Krypt from your messenger app.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PendingAmendmentUi(title: String, body: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
