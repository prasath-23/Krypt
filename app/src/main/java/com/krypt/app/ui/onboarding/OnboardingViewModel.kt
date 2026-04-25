package com.krypt.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.permission.PermissionClassification
import com.krypt.app.permission.PermissionKey
import com.krypt.app.permission.PermissionStateObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the feature-002 onboarding redesign (FR-022 through FR-029).
 *
 * Exposes [uiState] which the screen consumes to render the current step,
 * the mandatory-progress bar, the optional Skip button, and the Finish gate.
 *
 * The [permissionState] property is exposed so [OnboardingScreen] can call
 * [PermissionStateObserver.bind] inside a [DisposableEffect] — binding the
 * lifecycle-aware refresh to the onboarding screen's own lifecycle.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    val permissionState: PermissionStateObserver,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _stepIndex = MutableStateFlow(0)

    val uiState: StateFlow<OnboardingUiState> = combine(
        _stepIndex, permissionState.state,
    ) { index, permissions ->
        val step = OnboardingStep.ORDERED[index]
        val mandatoryGranted = permissions.entries
            .count { it.key in PermissionKey.MANDATORY && it.value }
        OnboardingUiState(
            currentStep = step,
            stepIndex = index,
            totalSteps = OnboardingStep.ORDERED.size,
            permissions = permissions,
            mandatoryGranted = mandatoryGranted,
            mandatoryTotal = PermissionKey.MANDATORY.size,
            canFinish = mandatoryGranted == PermissionKey.MANDATORY.size,
            canSkip = step.classification == PermissionClassification.OPTIONAL,
            isFirstStep = index == 0,
            isLastStep = index == OnboardingStep.ORDERED.lastIndex,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultState())

    /** Advance to the next step (no-op at the last step). */
    fun next() {
        val current = _stepIndex.value
        if (current < OnboardingStep.ORDERED.lastIndex) _stepIndex.value = current + 1
    }

    /** Go back one step (no-op at the first step). */
    fun back() {
        val current = _stepIndex.value
        if (current > 0) _stepIndex.value = current - 1
    }

    /** Advance to the next step; only valid on Optional steps. No-op on Mandatory. */
    fun skip() {
        val currentStep = OnboardingStep.ORDERED[_stepIndex.value]
        if (currentStep.classification == PermissionClassification.OPTIONAL) next()
    }

    /**
     * Mark onboarding complete and invoke [onComplete] so [MainRoute] navigates away.
     * No-op if [uiState.canFinish] is false.
     */
    fun tryFinish(onComplete: () -> Unit) {
        if (!uiState.value.canFinish) return
        viewModelScope.launch {
            settings.setOnboardingComplete(true)
            onComplete()
        }
    }

    private fun defaultState() = OnboardingUiState(
        currentStep = OnboardingStep.ORDERED[0],
        stepIndex = 0,
        totalSteps = OnboardingStep.ORDERED.size,
        permissions = emptyMap(),
        mandatoryGranted = 0,
        mandatoryTotal = PermissionKey.MANDATORY.size,
        canFinish = false,
        canSkip = false,
        isFirstStep = true,
        isLastStep = false,
    )
}

/** Snapshot of the onboarding UI state consumed by [OnboardingScreen]. */
data class OnboardingUiState(
    val currentStep: OnboardingStep,
    val stepIndex: Int,
    val totalSteps: Int,
    val permissions: Map<PermissionKey, Boolean>,
    val mandatoryGranted: Int,
    val mandatoryTotal: Int,
    val canFinish: Boolean,
    val canSkip: Boolean,
    val isFirstStep: Boolean,
    val isLastStep: Boolean,
)
