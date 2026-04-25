---
work_package_id: WP03
lane: planned
dependencies: []
base_branch: main
subtasks: [T012, T013, T014, T015, T016, T017, T018]
test_status: required
test_file: tests/002-manual-lock-and-permission-clarity/WP03-onboarding-redesign.spec.js
domain: frontend-craft
---

# WP03 - Onboarding redesign with live indicators

## Objective

Restructure the existing five-step onboarding into Mandatory + Optional groups, render the live ✓/✗ indicators (animated swap), wire the top progress bar to count only Mandatory permissions, and gate the "Finish" CTA on 3/3 Mandatory.

## Context

- **Spec:** FR-022, FR-023, FR-024, FR-025, FR-026, FR-027, FR-028, FR-029. US-1, US-2, US-3.
- **Depends on WP01:** consumes `PermissionKey`, `PermissionClassification`, and `PermissionStateObserver`.
- **Existing onboarding:** lives at `app/src/main/java/com/krypt/app/ui/onboarding/OnboardingScreen.kt` (feature 001 WP12). The five steps already exist, each with a "Grant" / "Open Settings" CTA. The current implementation is a simple step-counter; this WP rewires it on a Mandatory/Optional model.
- **Strings:** Reuse the existing `onboarding_step1_title` ... `onboarding_step5_grant` keys from `strings.xml`. Add new keys for the Skip CTA and the progress label only.
- **Theme:** Use the project's existing Material3 theme (`KryptTheme`). Success/error colours come from the existing `Color.kt` palette (extend if needed).

## Subtasks

### T012 --- `OnboardingStep` sealed type

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/onboarding/OnboardingStep.kt`

**Implementation:**
1. `sealed class OnboardingStep(val key: PermissionKey, val classification: PermissionClassification, val titleRes: Int, val bodyRes: Int, val grantRes: Int)`.
2. Five `data object` subclasses: `Accessibility`, `Overlay`, `Battery`, `DeviceAdmin`, `Notifications`. Each maps to its existing `R.string.onboarding_stepN_*` keys and carries the right `PermissionKey`.
3. `companion object { val ORDERED = listOf(Accessibility, Overlay, Battery, DeviceAdmin, Notifications) }`. (Mandatory steps come first so progress feels natural.)

### T013 --- `PermissionIndicator` Compose component

**Files to create:**
- `app/src/main/java/com/krypt/app/permission/PermissionIndicator.kt`

**Implementation:**
1. `@Composable fun PermissionIndicator(granted: Boolean, modifier: Modifier = Modifier)`:
   - `AnimatedContent` with `targetState = granted` and a `ContentTransform` of `fadeIn(tween(300)) + scaleIn(initialScale = 0.7f, animationSpec = tween(300))` togetherWith `fadeOut(tween(200))`.
   - For `true`: `Icon(Icons.Filled.CheckCircle, tint = MaterialTheme.colorScheme.tertiary or a custom green, contentDescription = stringResource(R.string.permission_indicator_granted))`.
   - For `false`: `Icon(Icons.Filled.Cancel, tint = MaterialTheme.colorScheme.error, contentDescription = stringResource(R.string.permission_indicator_denied))`.
2. Add the two new strings to `strings.xml`:
   - `permission_indicator_granted` → "Permission granted".
   - `permission_indicator_denied` → "Permission not granted".
3. Add `successGreen` colour to `Color.kt` (e.g., `0xFF34C759`) if Material3's tertiary doesn't read as green in both light/dark themes.

### T014 --- `OnboardingViewModel`

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/onboarding/OnboardingViewModel.kt`

**Implementation:**
1. `@HiltViewModel class OnboardingViewModel @Inject constructor(private val permissionState: PermissionStateObserver, private val settings: SettingsRepository) : ViewModel()`.
2. Internal state: current step index (0..4), derived `currentStep: OnboardingStep = OnboardingStep.ORDERED[index]`, `permissionMap: StateFlow<Map<PermissionKey, Boolean>>` proxied from `permissionState.state`.
3. Public state: `data class OnboardingUiState(val currentStep: OnboardingStep, val permissions: Map<PermissionKey, Boolean>, val mandatoryGranted: Int, val mandatoryTotal: Int = 3, val canFinish: Boolean, val canSkip: Boolean)`.
4. `fun next()` --- advance index by 1, clamp at the last step.
5. `fun back()` --- decrement, clamp at 0.
6. `fun skip()` --- only valid when `currentStep.classification == OPTIONAL`; advance index.
7. `fun finish()` --- only valid when `canFinish == true`; persist `settings.setOnboardingComplete(true)` and emit a one-shot `Channel<Unit>` consumed by the screen to navigate to Home.
8. `canFinish` = `permissions.entries.filter { it.key in PermissionKey.MANDATORY && it.value }.size == 3`.

### T015 --- `OnboardingScreen` rewrite

**Files to modify:**
- `app/src/main/java/com/krypt/app/ui/onboarding/OnboardingScreen.kt`

**Implementation:**
1. `@Composable fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel(), onComplete: () -> Unit)`:
   - `LaunchedEffect(Unit) { viewModel.permissionState.bind(LocalLifecycleOwner.current) }` --- wires the lifecycle-aware refresh.
   - Top of screen: `MandatoryProgressBar(granted = state.mandatoryGranted, total = state.mandatoryTotal)`. Accessible content description: `"Mandatory permissions: $granted of $total"`.
   - Step card body: title + body + a row containing the existing "Open settings" / "Grant" CTA AND a `PermissionIndicator(granted = state.permissions[step.key] ?: false)`.
   - Footer row:
     - Always show "Back" (disabled at index 0).
     - On Optional steps: show "Skip".
     - On the last step: show "Finish" (enabled only when `canFinish`); replaces the "Next" button.
     - On non-last steps: show "Next" (enabled always --- the user can advance even if the current permission isn't granted; the Mandatory gate is only on Finish).
2. `MandatoryProgressBar` is a small composable with five filled/empty pips OR a `LinearProgressIndicator(progress = granted / total.toFloat())`. Either works --- match existing onboarding aesthetic.
3. Modify the navigation: when Finish is tapped, call `onComplete()` which `MainActivity` wires to navigate to the new Home Screen (handled in WP05).

### T016 --- `PermissionIndicatorTest` (Compose UI)

**Files to create:**
- `app/src/test/java/com/krypt/app/permission/PermissionIndicatorTest.kt`

**Coverage:**
1. Robolectric + `createComposeRule()`. Render with `granted = true` → assert `onNodeWithContentDescription("Permission granted").assertIsDisplayed()` and the green icon node exists.
2. Render with `granted = false` → assert "Permission not granted" content description.
3. Toggle the boolean via state hoisting → assert the previous icon node is no longer displayed (cross-fade complete).

### T017 --- `OnboardingViewModelTest`

**Files to create:**
- `app/src/test/java/com/krypt/app/ui/onboarding/OnboardingViewModelTest.kt`

**Coverage:**
1. Use `MainDispatcherRule` from feature 001 test infra; MockK the `PermissionStateObserver` and `SettingsRepository`.
2. Initial state at index 0 is `Accessibility` (Mandatory), `canSkip = false`.
3. `next() x 3` reaches DeviceAdmin (Optional), `canSkip = true`.
4. `skip()` on a Mandatory step is a no-op (state unchanged).
5. With `permissions = { ACC=true, OVR=true, BAT=true }`, `canFinish = true`.
6. With `permissions = { ACC=true, OVR=true, BAT=false }`, `canFinish = false`.
7. `finish()` when not allowed does nothing (no `settings.setOnboardingComplete` call); when allowed, calls `setOnboardingComplete(true)` and emits the navigation channel.

### T018 --- `OnboardingScreenTest` (Compose UI)

**Files to create:**
- `app/src/test/java/com/krypt/app/ui/onboarding/OnboardingScreenTest.kt`

**Coverage:**
1. Inject a fake `PermissionStateObserver` returning `{ all=false }` initially. Render the screen.
2. Assert the indicator on the Accessibility step is the ✗ red icon.
3. Update the fake to `{ ACCESSIBILITY=true }` → recompose → assert ✓ green icon. (Use `composeTestRule.runOnIdle` and `mainClock.advanceTimeBy(300)` to walk past the cross-fade.)
4. Click "Next" three times to reach DeviceAdmin → assert "Skip" button visible.
5. Click "Skip" → assert advanced to Notifications.
6. Click "Skip" → reach last step. Update fake to `{ all=true }`. Assert "Finish" enabled.
7. Reset fake to `{ ACCESSIBILITY=true, OVERLAY=false, BATTERY=true }` → assert "Finish" disabled, mandatory progress reads 2/3.

## Definition of Done

- [ ] Onboarding builds with the new `OnboardingViewModel` and renders correctly under the existing `KryptTheme`.
- [ ] `PermissionIndicator` cross-fades between states in 200--500 ms (verified by Compose mainClock).
- [ ] Mandatory progress shows only Mandatory state. Optional permissions never advance the bar.
- [ ] "Finish" is disabled at <3/3 Mandatory.
- [ ] "Skip" is rendered only on Optional steps.
- [ ] No regression in existing onboarding strings --- the same `R.string.onboarding_stepN_*` keys are still consumed.
- [ ] `./gradlew :app:testDebugUnitTest --tests "com.krypt.app.ui.onboarding.*" --tests "com.krypt.app.permission.PermissionIndicatorTest"` passes.

## Risks and Edge cases

- **Cross-fade flicker** if `granted` flips many times during a single resume. Use `AnimatedContent` with `SizeTransform(clip = false)` to avoid layout thrash.
- **Theme contrast**: a custom green must pass WCAG AA against both the light and dark theme backgrounds. If Material3 `tertiary` is not green in your theme, add a `successGreen` colour and verify with `ContrastRatio.calculate(...)`.
- **Locale changes**: Activity recreates → ViewModel survives via Hilt's `viewModelScope`; `permissionState.bind` re-runs on the new lifecycle owner. Verified in T018 by re-creating the compose rule.
- **API 33 notification permission timing**: When the user first launches Krypt on API 33, the system may not have prompted yet; `areNotificationsEnabled()` returns `false`. Optional step is skip-able, so this is fine.

## Reviewer guidance

- Run on a device, ideally Xiaomi/MIUI: grant permissions one at a time and watch the indicators flip.
- Verify via `adb shell` that `Settings.canDrawOverlays` is in fact off then on, then back to off when revoked, and the indicator reflects each change after returning to the app.
- Implement command: `polaris implement WP03 --base WP01`.
