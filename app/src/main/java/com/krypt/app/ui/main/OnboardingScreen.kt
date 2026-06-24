package com.krypt.app.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.permission.PermissionIndicator
import com.krypt.app.ui.onboarding.OnboardingStep
import com.krypt.app.ui.onboarding.OnboardingUiState
import com.krypt.app.ui.onboarding.OnboardingViewModel

/**
 * 5-step onboarding wizard redesigned for feature 002.
 *
 * - Mandatory steps (Accessibility, Overlay, Battery): no Skip button, must
 *   be granted before "Finish" is enabled.
 * - Optional steps (Device Admin, Notifications): Skip button visible;
 *   bypassed gracefully (FR-029).
 * - Each step shows a live ✓/✗ indicator (FR-023) that updates on onResume.
 * - Mandatory progress bar at top counts only the 3 Mandatory permissions (FR-026).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        viewModel.permissionState.bind(lifecycleOwner)
        onDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MandatoryProgressBar(
                granted = state.mandatoryGranted,
                total = state.mandatoryTotal,
            )

            Text(
                text = stringResource(
                    R.string.onboarding_step_of,
                    state.stepIndex + 1,
                    state.totalSteps,
                ),
                style = MaterialTheme.typography.labelMedium,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                StepContent(state = state, step = state.currentStep)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = !state.isFirstStep,
                    onClick = viewModel::back,
                ) { Text(stringResource(R.string.onboarding_back)) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(visible = state.canSkip) {
                        TextButton(onClick = viewModel::skip) {
                            Text(stringResource(R.string.onboarding_skip))
                        }
                    }

                    if (state.isLastStep) {
                        Button(
                            enabled = state.canFinish,
                            onClick = { viewModel.tryFinish(onComplete) },
                        ) { Text(stringResource(R.string.onboarding_finish)) }
                    } else {
                        Button(onClick = viewModel::next) {
                            Text(stringResource(R.string.onboarding_next))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mandatory-only progress bar (FR-026). Optional permissions do not contribute.
 */
@Composable
private fun MandatoryProgressBar(granted: Int, total: Int) {
    val desc = stringResource(R.string.onboarding_mandatory_progress, granted, total)
    Column(modifier = Modifier.semantics { contentDescription = desc }) {
        LinearProgressIndicator(
            progress = { granted.toFloat() / total.coerceAtLeast(1).toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Content body for the current [OnboardingStep]: title, body, grant CTA, and
 * the live [PermissionIndicator].
 */
@Composable
private fun StepContent(state: OnboardingUiState, step: OnboardingStep) {
    val context = LocalContext.current
    val isGranted = state.permissions[step.key] ?: false

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(step.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            PermissionIndicator(
                granted = isGranted,
                modifier = Modifier.size(28.dp),
            )
        }

        Text(
            text = stringResource(step.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                when (step) {
                    OnboardingStep.Accessibility ->
                        activityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

                    OnboardingStep.Overlay ->
                        activityLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )

                    OnboardingStep.Battery ->
                        activityLauncher.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )

                    OnboardingStep.DeviceAdmin -> {
                        val adminComponent = android.content.ComponentName(
                            context, com.krypt.app.service.KryptDeviceAdminReceiver::class.java
                        )
                        activityLauncher.launch(
                            Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_explanation))
                            }
                        )
                    }

                    OnboardingStep.Notifications -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            },
        ) { Text(stringResource(step.grantRes)) }
    }
}
