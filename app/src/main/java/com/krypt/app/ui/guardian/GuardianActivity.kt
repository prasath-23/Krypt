package com.krypt.app.ui.guardian

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.krypt.app.R
import com.krypt.app.deeplink.DeepLinkScheme
import com.krypt.app.guardian.GuardianPinScreen
import com.krypt.app.ui.components.BodyText
import com.krypt.app.ui.components.Eyebrow
import com.krypt.app.ui.components.KryptPage
import com.krypt.app.ui.components.ScreenTitle
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Deep-link host for `krypt://request` URLs: the Guardian approval screen.
 * Never blocked by [com.krypt.app.service.AppLockerAccessibilityService]
 * (it is part of Krypt), so Guardians can approve requests even on devices
 * where Krypt also protects apps (FR-011). Screenshots are blocked
 * (`FLAG_SECURE`). An [AppCompatActivity] so the Appearance setting's night
 * mode applies to it on every Android version.
 *
 * `krypt://approve` is handled by ApprovalTrampolineActivity (WP22). The
 * legacy `krypt://pair` / `krypt://paired` links (superseded by Amendment 1)
 * are no longer routed: their screens could overwrite this device's PBKDF2
 * settings and needed API 33+ crypto.
 */
@AndroidEntryPoint
class GuardianActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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

/** A link this screen can't open, e.g. a legacy pairing link. */
@Composable
private fun UnknownLink() {
    KryptPage {
        Eyebrow(text = stringResource(R.string.eyebrow_krypt))
        Spacer(Modifier.height(KryptSpace.s2))
        ScreenTitle(text = stringResource(R.string.link_unknown_title))
        Spacer(Modifier.height(KryptSpace.s2))
        BodyText(text = stringResource(R.string.link_unknown_body))
    }
}
