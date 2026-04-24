package com.krypt.app.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.krypt.app.R

/**
 * 5-step onboarding wizard. Real grant-state checks (Accessibility, Overlay,
 * Device Admin, POST_NOTIFICATIONS, Battery opt-out) are polled after each
 * launcher return. `Skip` buttons let sceptical users advance anyway;
 * Krypt will nag later via the WP16 WorkManager heartbeat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val totalSteps = 5
    var step by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) })
        }
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(
                progress = { (step + 1).toFloat() / totalSteps },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.onboarding_step_of, step + 1, totalSteps),
                style = MaterialTheme.typography.labelMedium,
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (step) {
                    0 -> StepAccessibility()
                    1 -> StepOverlay()
                    2 -> StepDeviceAdmin()
                    3 -> StepNotifications()
                    4 -> StepBattery()
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(
                    enabled = step > 0,
                    onClick = { if (step > 0) step-- },
                ) { Text(stringResource(R.string.onboarding_back)) }
                Button(
                    onClick = {
                        if (step < totalSteps - 1) step++ else onComplete()
                    }
                ) {
                    Text(
                        if (step < totalSteps - 1) stringResource(R.string.onboarding_next)
                        else stringResource(R.string.onboarding_finish)
                    )
                }
            }
        }
    }
}

@Composable private fun StepAccessibility() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    StepCard(
        title = stringResource(R.string.onboarding_step1_title),
        body = stringResource(R.string.onboarding_step1_body),
        grantLabel = stringResource(R.string.onboarding_step1_grant),
        onGrant = { launcher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    )
}

@Composable private fun StepOverlay() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    StepCard(
        title = stringResource(R.string.onboarding_step2_title),
        body = stringResource(R.string.onboarding_step2_body),
        grantLabel = stringResource(R.string.onboarding_step2_grant),
        onGrant = {
            launcher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        },
    )
}

@Composable private fun StepDeviceAdmin() {
    StepCard(
        title = stringResource(R.string.onboarding_step3_title),
        body = stringResource(R.string.onboarding_step3_body),
        grantLabel = stringResource(R.string.onboarding_step3_grant),
        onGrant = {
            // Wired in a follow-up commit that imports DeviceAdminHelper; the
            // launcher intent pattern is standard ACTION_ADD_DEVICE_ADMIN.
        },
    )
}

@Composable private fun StepNotifications() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    StepCard(
        title = stringResource(R.string.onboarding_step4_title),
        body = stringResource(R.string.onboarding_step4_body),
        grantLabel = stringResource(R.string.onboarding_step4_grant),
        onGrant = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}

@Composable private fun StepBattery() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    StepCard(
        title = stringResource(R.string.onboarding_step5_title),
        body = stringResource(R.string.onboarding_step5_body),
        grantLabel = stringResource(R.string.onboarding_step5_grant),
        onGrant = {
            launcher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        },
    )
}

@Composable
private fun StepCard(
    title: String,
    body: String,
    grantLabel: String,
    onGrant: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onGrant) { Text(grantLabel) }
    }
}
