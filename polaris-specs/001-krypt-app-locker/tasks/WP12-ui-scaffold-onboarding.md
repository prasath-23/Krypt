---
work_package_id: WP12
lane: "done"
dependencies: [WP01]
base_branch: 001-krypt-app-locker-WP11
base_commit: 2f557214fec377607af950fe9e724178204e8a02
created_at: '2026-04-24T07:36:17.626742+00:00'
subtasks: [T055, T056, T057, T058, T059]
test_status: required
test_file: tests/e2e/WP12-wp12-ui-scaffold-onboarding.spec.js
agent: "claude"
reviewed_by: "Prasath Kumar K"
review_status: "approved"
---

# WP12 - UI scaffold: MainActivity + Compose theme + onboarding wizard

## Objective

Build the main Compose + Material3 entry point: themed `MainActivity`, a multi-step onboarding wizard that walks the Administrator through granting Accessibility, Overlay, Device-Admin, POST_NOTIFICATIONS, and Battery-optimisation privileges, and a placeholder Home screen. Routing between Onboarding and Home is driven by `SettingsRepository.onboardingComplete`.

## Context

- **Spec US-1.**
- **Plan:** "Jetpack Compose + Material3 for Activities" (lockbox decision).
- **Dependencies:** WP01 scaffold for Activity + Application.
- **Transitively used:** the onboarding wizard opens intents to grant privileges that other WPs provide (WP10 Accessibility, WP09 overlay, WP11 Device-Admin, WP07 Notifications). WP12 can be implemented with stub-handler callbacks; wiring to real grant-state checks happens when those WPs land (later PRs in the WP12 branch or via follow-up integration commits).

## Subtasks

### T055 - Material3 theme (colors, typography, shapes)

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/theme/Color.kt`
- `app/src/main/java/com/krypt/app/ui/theme/Type.kt`
- `app/src/main/java/com/krypt/app/ui/theme/Shape.kt`
- `app/src/main/java/com/krypt/app/ui/theme/KryptTheme.kt`
- `app/src/main/res/values/themes.xml` (Android theme; hosts `Theme.Krypt` with NoActionBar base)
- `app/src/main/res/values-night/themes.xml` (dark variant)

**Implementation steps:**

1. `Color.kt` - Material3 brand tokens: `Primary = #2E1A47` (deep royal purple), `OnPrimary = #FFFFFF`, `Secondary = #F3C98B` (warm amber for accent), `Error = #B3261E`, etc. Define both light and dark schemes.
2. `Type.kt` - default `Typography` instance; display/headline/title/body/label variants.
3. `Shape.kt` - default `Shapes` using 12dp small / 16dp medium / 24dp large corners.
4. `KryptTheme.kt`:
   ```
   @Composable
   fun KryptTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
     val scheme = if (darkTheme) DarkColorScheme else LightColorScheme
     MaterialTheme(colorScheme = scheme, typography = KryptTypography, shapes = KryptShapes, content = content)
   }
   ```
5. Android `themes.xml` - base `Theme.Krypt` extends `Theme.Material3.DayNight.NoActionBar`; declares `windowBackground`, `statusBarColor`, `navigationBarColor`.

**Validation.** `@Preview` functions in Studio render with expected colors.

### T056 - MainActivity (Compose host + routing)

**Files to create / modify:**
- `app/src/main/java/com/krypt/app/ui/main/MainActivity.kt` (replace WP01 stub)

**Implementation steps:**

1. `@AndroidEntryPoint class MainActivity : ComponentActivity()`:
   - `@Inject lateinit var settings: SettingsRepository`
   - `override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { KryptTheme { MainRoute() } } }`.
2. `@Composable fun MainRoute(viewModel: MainViewModel = hiltViewModel())`:
   - Collects `viewModel.onboardingComplete: StateFlow<Boolean>`.
   - `if (onboardingComplete) HomeScreen() else OnboardingScreen(onComplete = viewModel::markOnboardingComplete)`.
3. `@HiltViewModel class MainViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel()`:
   - `val onboardingComplete: StateFlow<Boolean> = settings.settings.map { it.onboardingComplete }.stateIn(viewModelScope, SharingStarted.Eagerly, false)`.
   - `fun markOnboardingComplete() { viewModelScope.launch { settings.setOnboardingComplete(true) } }`.

**Validation.** Launch the app; MainActivity renders correctly in both modes depending on `settings.onboarding_complete`.

### T057 - OnboardingScreen (5-step wizard)

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/main/OnboardingScreen.kt`
- `app/src/main/java/com/krypt/app/ui/main/OnboardingViewModel.kt` (optional; can stay stateless)

**Implementation steps:**

1. Top-level `@Composable fun OnboardingScreen(onComplete: () -> Unit)`:
   - `Scaffold(topBar = { TopAppBar(title = { Text("Welcome to Krypt") }) })`.
   - `Column` with `LinearProgressIndicator(progress / totalSteps)` + current step body.
   - Bottom `Row` with "Back" + "Next"/"Grant & Next" buttons.
2. 5 steps (each a `@Composable` pane):
   - **Step 1: Accessibility.** Description of why; "Open Accessibility Settings" button calls `launcher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))`. Post-return, check `AccessibilityManager.getEnabledAccessibilityServiceList` for Krypt's service; gray-out Next until granted.
   - **Step 2: Display over other apps.** Button launches `Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${packageName}"))`. Post-return, `Settings.canDrawOverlays(ctx)` gates Next.
   - **Step 3: Device Admin.** Uses `DeviceAdminHelper` (WP11). `launcher.launch(adminHelper.createActivationIntent(...))`; post-return, `adminHelper.isActive()` gates Next.
   - **Step 4: Notifications (API 33+).** Uses `NotificationPermissionHelper`. On API <33, auto-advance.
   - **Step 5: Battery optimisation.** `launcher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${packageName}")))`. Post-return, `PowerManager.isIgnoringBatteryOptimizations(pkg)` gates Next.
3. On step 5 completion, call `onComplete()`.
4. Each step has a skip option marked "Not recommended" that reveals a confirmation dialog (allows sceptical users to proceed; onboarding_complete still set true; Krypt will nag later).

**Validation.** Compose preview for each step. Manual walkthrough on device.

### T058 - HomeScreen placeholder

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/main/HomeScreen.kt`

**Implementation steps:**

1. `@Composable fun HomeScreen(viewModel: HomeViewModel = hiltViewModel())`:
   - Top app bar "Krypt".
   - Card: "Pairing status" - shows pairing state from `GuardianRepository` (tied to WP13 but placeholder-safe).
   - Card: "Locked apps: N" (count from `LockedAppsRepository.observeLockedApps().collectAsStateWithLifecycle()`).
   - Button: "Re-share pairing link" (enabled if paired as Subject).
   - Button: "Revoke & re-pair" (destructive, confirmed via dialog).
2. `@HiltViewModel class HomeViewModel @Inject constructor(private val lockedAppsRepo: LockedAppsRepository, private val guardianRepo: GuardianRepository) : ViewModel()` exposes the observable state.

**Validation.** Renders without crashes when repos are empty.

### T059 - Compose UI tests

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/ui/main/MainActivityRoutingTest.kt`
- `app/src/androidTest/java/com/krypt/app/ui/main/OnboardingScreenUiTest.kt`

**Implementation steps:**

1. `MainActivityRoutingTest`:
   - Uses `@HiltAndroidTest` + `HiltAndroidRule` + `createComposeRule()`.
   - Replaces `SettingsRepository` with a fake whose `onboardingComplete` is configurable.
   - Test `whenNotOnboarded_showsOnboarding`: fake returns false; compose surface matches a text on OnboardingScreen ("Step 1 of 5" or similar).
   - Test `whenOnboarded_showsHome`: fake returns true; surface matches a HomeScreen element.
2. `OnboardingScreenUiTest`:
   - Steps through pages via button clicks; asserts step index advances.
   - "Skip" produces a confirmation dialog.
   - Final step completion calls the `onComplete` callback (captured via a test `Fun0`).

**Validation.** `./gradlew :app:connectedDebugAndroidTest --tests "*MainActivityRoutingTest*" "*OnboardingScreenUiTest*"` green.

## Test Strategy

- **Unit:** not much - Compose is UI-bound.
- **Instrumented Compose UI tests:** T059.
- **Manual:** walk through onboarding on a clean install.

## Definition of Done

- [ ] Theme compiles; `@Preview` functions render.
- [ ] MainActivity routes correctly between Onboarding and Home based on `onboarding_complete`.
- [ ] OnboardingScreen has all 5 steps with their grant-launcher intents.
- [ ] Each step shows a "Granted" checkmark once the permission is actually granted (re-entry after returning from Settings).
- [ ] Compose UI tests green.
- [ ] Manual walkthrough on a clean install: all steps can be granted, Next unlocks correctly, final Next transitions to HomeScreen.

## Risks + Edge cases

- **`Settings.canDrawOverlays` returns true before user taps back.** Some OEMs auto-grant on API 29+ even without the user granting; verify via manual test on multiple devices.
- **DeviceAdminHelper on WP11 not yet implemented.** WP12 depends on WP11 IF the onboarding step is to actually grant Device Admin. Options:
  (a) Add `dependencies: [WP01, WP11]` and delay WP12 to after WP11.
  (b) Stub step 3 with a "TODO: wire in WP11" and make the final PR merge require both.
  Chosen: WP12 has base `WP01`; full Device-Admin wiring happens as a small follow-up inside the merged WP branch once WP11 is available. The implement-agent should notice WP11 is complete and update the step.
- **Battery-optimisation deep-link reliability.** Some OEMs show a different screen than the standard one. Document known OEM quirks (MIUI, EMUI) in Step 5 help text.
- **System theme light/dark auto-switch.** Compose rebuilds on config change; verify the wizard state is preserved via `rememberSaveable` on the step index.

## Reviewer Guidance

- Confirm every `Intent` in onboarding steps has a fallback `resolveActivity` check - show a user-facing message if the OEM lacks the expected Settings activity.
- Verify no hardcoded strings; grep `strings.xml` for each wizard copy line.
- Check that `onComplete` is called only after step 5; skipping earlier does not short-circuit.
- Run Compose UI tests and paste screenshot attachments in the PR.

## Next command

```
polaris implement WP12 --base WP01
```

## Activity Log

- 2026-04-24T07:38:19Z -- claude -- lane=doing -- d
- 2026-04-24T07:38:25Z -- claude -- lane=testing -- t
- 2026-04-24T07:38:35Z -- claude -- lane=for_review -- r
- 2026-04-24T07:38:49Z -- claude -- lane=done -- m
