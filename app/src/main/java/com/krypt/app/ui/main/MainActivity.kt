package com.krypt.app.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable
fun MainRoute(viewModel: MainViewModel = hiltViewModel()) {
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    if (onboardingComplete) {
        HomeScreen()
    } else {
        OnboardingScreen(onComplete = viewModel::markOnboardingComplete)
    }
}

@Preview(showBackground = true)
@Composable
private fun MainRoutePreview() {
    KryptTheme {
        HomeScreen()
    }
}
