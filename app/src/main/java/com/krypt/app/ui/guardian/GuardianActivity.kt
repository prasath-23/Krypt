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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.deeplink.DeepLinkScheme
import com.krypt.app.guardian.GuardianPinScreen
import com.krypt.app.ui.theme.KryptTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Deep-link host for `krypt://request` URLs: the Guardian approval screen.
 * Never blocked by [com.krypt.app.service.AppLockerAccessibilityService]
 * (it is part of Krypt), so Guardians can approve requests even on devices
 * where Krypt also protects apps (FR-011).
 *
 * `krypt://approve` is handled by ApprovalTrampolineActivity (WP22). The
 * legacy `krypt://pair` / `krypt://paired` links (superseded by Amendment 1)
 * are no longer routed: their screens could overwrite this device's PBKDF2
 * settings and needed API 33+ crypto.
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
    if (data != null &&
        data.scheme == DeepLinkScheme.SCHEME &&
        data.authority == DeepLinkScheme.AUTHORITY_REQUEST
    ) {
        GuardianPinScreen(incomingUrl = data.toString())
    } else {
        UnknownLink()
    }
}

@Composable
private fun UnknownLink() {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.link_unknown_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.link_unknown_body), style = MaterialTheme.typography.bodyMedium)
    }
}
