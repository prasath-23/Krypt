# Task Breakdown: 002 Manual Lock + Permission Clarity

**Spec:** [spec.md](./spec.md) | **Plan:** [plan.md](./plan.md) | **Control map:** [control-map.md](./control-map.md)

## Work Package Map

| WP | Title | Domain | Depends on | Subtasks | Lane |
|----|-------|--------|-----------|----------|------|
| WP01 | Permission status infrastructure | backend-logic | --- | 6 | planned |
| WP02 | Installed apps repository | backend-logic | --- | 5 | planned |
| WP03 | Onboarding redesign with live indicators | frontend-craft | WP01 | 7 | planned |
| WP04 | Home Screen + manual lock toggle | frontend-craft | WP02 | 6 | planned |
| WP05 | Navigation rewire + integration e2e | testing-specialist | WP03, WP04 | 5 | planned |

WP01 and WP02 are independent and can run in parallel. WP03 and WP04 can also run in parallel after their respective dependencies. WP05 closes the loop and sequences last.

## WP01 --- Permission status infrastructure

**Goal:** Provide a clean, lifecycle-aware way to read and observe the five permissions Krypt cares about. This is the data layer for the onboarding indicators.

**Subtasks:**
- [x] T001 --- Create `PermissionKey` enum (ACCESSIBILITY, OVERLAY, BATTERY, DEVICE_ADMIN, NOTIFICATIONS) and `PermissionClassification` enum (MANDATORY, OPTIONAL).
- [x] T002 --- Define `PermissionStatusProbe` interface and implement `AndroidPermissionStatusProbe` that queries OS state for each `PermissionKey`.
- [x] T003 --- Implement `PermissionStateObserver` that exposes `StateFlow<Map<PermissionKey, Boolean>>`, re-runs all probes on `onResume`, and subscribes to `AccessibilityManager.AccessibilityStateChangeListener` for sub-second push updates where supported.
- [x] T004 --- Add Hilt bindings for `PermissionStatusProbe` and `PermissionStateObserver` (new `UiModule.kt` or extension of `CoreModule`).
- [x] T005 --- Robolectric unit tests for `AndroidPermissionStatusProbe` (one assertion per `PermissionKey`).
- [x] T006 --- Unit tests for `PermissionStateObserver` (verify lifecycle re-poll + listener-driven updates with a fake probe).

## WP02 --- Installed apps repository

**Goal:** Build the data source that powers the Home Screen --- every non-system installed app, sorted by display name, with deferred icon loading.

**Subtasks:**
- [x] T007 --- Define `InstalledAppMeta(packageName, displayName)` data class.
- [x] T008 --- Define `InstalledAppsRepository` interface; implement `AndroidInstalledAppsRepository` using `PackageManager.getInstalledApplications` filtered by `(flags and FLAG_SYSTEM) == 0` plus the existing curated allowlist used by feature 001's auto-locker.
- [x] T009 --- Implement `AppIconCache` (LRU `Bitmap` cache, ~16 MB cap) and a deferred icon loader that returns a callable `() -> Drawable` for each row.
- [x] T010 --- Hilt binding for `InstalledAppsRepository` and `AppIconCache`.
- [x] T011 --- Unit tests: system-app filtering, alphabetical ordering, icon-cache eviction behaviour.

## WP03 --- Onboarding redesign with live indicators

**Goal:** Restructure onboarding to honour Mandatory vs Optional, render the live ✓/✗ indicators with smooth transitions, and gate "Finish" on 3/3 Mandatory.

**Subtasks:**
- [x] T012 --- Define `OnboardingStep` sealed type carrying `PermissionKey` and `PermissionClassification`. Five canonical steps (Accessibility, Overlay, Battery, DeviceAdmin, Notifications).
- [x] T013 --- Build `PermissionIndicator` Compose component: animated ✓/✗ glyph swap (200--500 ms cross-fade or scale), correct semantic colours (`success.green`, `error.red`), accessible `contentDescription`.
- [x] T014 --- Implement `OnboardingViewModel`: drives current step, exposes Mandatory progress (count granted out of 3), gates `canFinish` on 3/3 Mandatory, exposes Skip action that advances on Optional steps only.
- [x] T015 --- Rewire `OnboardingScreen`: top progress bar showing only Mandatory progress, "Skip" button rendered only on Optional steps, indicator component rendered on every step card, "Finish" CTA disabled until `canFinish`.
- [x] T016 --- Compose UI test for `PermissionIndicator` (granted vs not, content-description per state).
- [x] T017 --- `OnboardingViewModelTest` (state machine: skip on Optional, mandatory progress, can-finish gate, transitions when probe state changes).
- [x] T018 --- Compose UI test for `OnboardingScreen` (skip optional step navigates forward, finish disabled at <3/3, finish enabled at 3/3).

## WP04 --- Home Screen + manual lock toggle

**Goal:** Replace the existing post-onboarding placeholder Home with a searchable installed-apps list that supports the one-way Lock toggle.

**Subtasks:**
- [x] T019 --- Implement `HomeViewModel`: combines `InstalledAppsRepository.allInstalled()` with `LockedAppsRepository.allLockedFlow()`; exposes `StateFlow<List<InstalledAppRow>>` filtered by an editable search query.
- [x] T020 --- Build `InstalledAppRow` composable: icon (lazy-loaded), display name, Material 3 `Switch`. The switch's `onCheckedChange` is gated by `isLocked` --- see T022.
- [x] T021 --- Build `HomeScreen` composable: search `TextField` at top + `LazyColumn` of `InstalledAppRow` items.
- [x] T022 --- Implement the one-way toggle logic. If `isLocked == false` and the user taps to ON → call `LockedAppsRepository.lock(packageName)`. If `isLocked == true` and the user taps → DO NOT change state, instead invoke a `RequestUnlockShareIntent` use case that builds the `krypt://request?...` URL and starts an `ACTION_SEND` chooser. The view model exposes the share intent as a one-shot `Channel<Intent>` consumed by the screen via `LaunchedEffect`.
- [x] T023 --- `HomeViewModelTest`: search filtering, `lock(pkg)` writes through repository, attempted unlock emits a `RequestUnlockShareIntent` carrying a URL parseable by `UnlockRequestParser`.
- [x] T024 --- Compose UI test for `HomeScreen` (typing in search filters list, toggle ON locks instantly, toggle OFF attempt does NOT change state and emits expected intent via test double).

## WP05 --- Navigation rewire + integration e2e

**Goal:** Wire the new Home Screen into the post-onboarding navigation, and add device-resident e2e tests that exercise the full flow end-to-end.

**Subtasks:**
- [x] T025 --- Modify `MainActivity` (`AppScreen` enum / `MainRoute` navigation) so that, on `MasterKeyStore.isConfigured() == true` AND `onboardingComplete == true`, the route resolves to `HomeScreen` (not the legacy placeholder).
- [x] T026 --- Wire the share-intent dispatcher in `HomeScreen` (collect `viewModel.shareIntents` and call `startActivity(Intent.createChooser(...))`).
- [x] T027 --- androidTest `OnboardingMandatoryGateTest`: launches onboarding with 0 permissions; programmatically grants Accessibility/Overlay/Battery; asserts indicators flip to ✓ within 500 ms of `onResume`; asserts Finish becomes enabled at 3/3 and disabled at 2/3.
- [x] T028 --- androidTest `ManualLockToggleE2ETest`: from Home, find an unlocked target package, tap toggle; assert `LockedAppsRepository.isLocked(pkg)` is true; relaunch the package and assert the existing Locker Screen overlay covers it within 200 ms (re-using the AndroidX Test orchestration from feature 001 WP23).
- [x] T029 --- androidTest `AttemptedUnlockShareSheetTest`: from Home, find a locked target package, tap toggle; intercept the `ACTION_SEND` intent via `Intents.intending(...)`; assert the URL parses through `UnlockRequestParser` and resolves to `Outcome.Ok` with `targetPackage == intendedPackage`.

## Parallelization opportunities

- WP01 and WP02 are completely independent (different packages, no shared files). Run in parallel.
- WP03 depends only on WP01, WP04 depends only on WP02 --- can run in parallel after their respective foundations land.
- WP05 must run last (integrates both upstream halves).

## Test placement

- Unit & ViewModel tests: `app/src/test/java/com/krypt/app/{permission|ui/onboarding|ui/home}/`.
- Compose UI tests with Robolectric: same `test/` source set under each ui/* package.
- Device e2e: `app/src/androidTest/java/com/krypt/app/e2e/`.
- Polaris feature-scoped regression entry: `tests/002-manual-lock-and-permission-clarity/`.
