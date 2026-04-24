package com.krypt.app.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.data.PairingRole
import com.krypt.app.ui.pairing.PairingRoleChooserScreen
import com.krypt.app.ui.pairing.SubjectPairScreen
import com.krypt.app.ui.theme.KryptTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class AppScreen { HOME, PAIRING_ROLE_CHOOSER, SUBJECT_PAIR }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KryptTheme { MainRoute() }
        }
    }
}

@Composable
fun MainRoute(viewModel: MainViewModel = hiltViewModel()) {
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }

    if (!onboardingComplete) {
        OnboardingScreen(onComplete = viewModel::markOnboardingComplete)
    } else {
        when (screen) {
            AppScreen.HOME -> HomeScreen(onPairClick = { screen = AppScreen.PAIRING_ROLE_CHOOSER })
            AppScreen.PAIRING_ROLE_CHOOSER -> PairingRoleChooserScreen(
                onRoleChosen = { role ->
                    when (role) {
                        PairingRole.SUBJECT_OF_GUARDIAN -> screen = AppScreen.SUBJECT_PAIR
                        // Guardian pairing is triggered by opening a krypt://pair deep link
                        // from the Subject device — handled by GuardianActivity.
                        PairingRole.GUARDIAN_OF_SUBJECT -> screen = AppScreen.HOME
                    }
                }
            )
            AppScreen.SUBJECT_PAIR -> SubjectPairScreen()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainRoutePreview() {
    KryptTheme {
        HomeScreen()
    }
}
