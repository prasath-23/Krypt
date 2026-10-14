package com.krypt.app.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.notifications.NotificationHelper
import com.krypt.app.ui.setup.PinSetupScreen
import com.krypt.app.ui.theme.KryptTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Krypt's launcher activity. An [AppCompatActivity] so the Appearance
 * setting's night mode applies to it on every Android version.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** The app a "New app protected" notification pointed at; Home highlights it once. */
    private var highlightPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        highlightPackage = intent.notifiedPackage()
        setContent {
            KryptTheme {
                MainRoute(
                    viewModel = viewModel,
                    highlightPackage = highlightPackage,
                    onHighlightShown = ::clearHighlight,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.notifiedPackage()?.let { highlightPackage = it }
    }

    override fun onStop() {
        super.onStop()
        // Leaving Krypt (Home button, Recents, screen off) closes the Home
        // Screen, so the Subject cannot reopen it without the Guardian PIN.
        if (!isChangingConfigurations) viewModel.lockAppEntry()
    }

    private fun clearHighlight() {
        highlightPackage = null
        intent.removeExtra(NotificationHelper.EXTRA_PACKAGE)
    }

    private fun Intent.notifiedPackage(): String? = getStringExtra(NotificationHelper.EXTRA_PACKAGE)
}

/**
 * Top-level navigation, Amendment 1 flow:
 *   onboarding incomplete   -> FirstRunRoute: who is this phone for, then
 *                              the permission checklist (or, on a
 *                              Guardian's phone, just Paste a link)
 *   onboarding complete,
 *   MasterKey not configured-> PinSetupScreen (Guardian types PIN on Subject
 *                              device; replaces the legacy X25519 pairing
 *                              round-trip — see polaris-specs/001-krypt-app-
 *                              locker/spec.md "Amendment 1")
 *   not unlocked this visit -> AppEntryPinScreen (the Guardian PIN gate)
 *   otherwise               -> HomeScreen
 *
 * Until the stored state has been read, only the background shows.
 */
@Composable
fun MainRoute(
    viewModel: MainViewModel = hiltViewModel(),
    highlightPackage: String? = null,
    onHighlightShown: () -> Unit = {},
) {
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val masterKeyConfigured by viewModel.masterKeyConfigured.collectAsStateWithLifecycle()
    val appEntryUnlocked by viewModel.appEntryUnlocked.collectAsStateWithLifecycle()

    when {
        !ready -> Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        !onboardingComplete -> FirstRunRoute(onComplete = viewModel::markOnboardingComplete)
        !masterKeyConfigured -> PinSetupScreen(
            onDone = viewModel::refreshMasterKeyConfigured,
        )
        !appEntryUnlocked -> AppEntryPinScreen(
            onSuccess = viewModel::markAppEntryUnlocked,
        )
        else -> HomeScreen(
            highlightPackage = highlightPackage,
            onHighlightShown = onHighlightShown,
        )
    }
}
