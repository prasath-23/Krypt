package com.krypt.app.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.ui.setup.PinSetupScreen
import com.krypt.app.ui.theme.KryptTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KryptTheme { MainRoute() }
        }
    }
}

/**
 * Top-level navigation, Amendment 1 flow:
 *   onboarding incomplete   -> OnboardingScreen (permission wizard)
 *   onboarding complete,
 *   MasterKey not configured-> PinSetupScreen (Guardian types PIN on Subject
 *                              device; replaces the legacy X25519 pairing
 *                              round-trip — see polaris-specs/001-krypt-app-
 *                              locker/spec.md "Amendment 1")
 *   both complete           -> HomeScreen
 *
 * The legacy `PairingRoleChooserScreen` / `SubjectPairScreen` path is no
 * longer reachable from the Home surface. Those files remain in the tree
 * for history; see WP04 / WP13 in the shipped work packages.
 */
@Composable
fun MainRoute(viewModel: MainViewModel = hiltViewModel()) {
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val masterKeyConfigured by viewModel.masterKeyConfigured.collectAsStateWithLifecycle()

    when {
        !onboardingComplete -> OnboardingScreen(onComplete = viewModel::markOnboardingComplete)
        !masterKeyConfigured -> PinSetupScreen(
            onDone = viewModel::refreshMasterKeyConfigured,
        )
        else -> HomeScreen()
    }
}

@Preview(showBackground = true)
@Composable
private fun MainRoutePreview() {
    KryptTheme {
        HomeScreen()
    }
}
