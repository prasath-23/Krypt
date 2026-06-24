# Implementation Plan: Manual App Lock + Permission Clarity

**Branch**: `main` | **Date**: 2026-04-25 | **Spec**: [spec.md](./spec.md)
**Feature**: 002-manual-lock-and-permission-clarity

## Summary

Two cohesive UX additions to Krypt's Subject app, sharing zero new infrastructure but each touching distinct parts of the existing stack:

1. **Permission Onboarding Redesign** --- split the five-step onboarding into Mandatory (Accessibility, Overlay, Battery) and Optional (Device Admin, Notifications) blocks. Each step shows a live ✓/✗ indicator that re-evaluates on `Activity.onResume` (and via lifecycle observers where the OS supports them). A top progress strip counts only Mandatory progress; Optional steps gain a "Skip" button. Setup can finish only at 3/3 Mandatory.
2. **Home Screen as Manual App Lock Manager** --- the post-onboarding home becomes a searchable list of installed (non-system) apps with a one-way Material toggle per row. Toggle ON = instant lock via `LockedAppsRepository`. Toggle OFF attempt = does NOT unlock; instead invokes `UnlockRequestBuilder` (from feature 001) to dispatch a `krypt://request` URL through the system Share sheet.

The two features ship together because they sit on the same screen sequence and reuse the same DI graph; splitting them would force two passes over `MainActivity` navigation.

## Technical Context

**Language/Version**: Kotlin 1.9, Android compileSdk 35 / minSdk 29 (matches feature 001).
**Primary Dependencies**: Jetpack Compose (Material3), Hilt, Room, AndroidX Lifecycle, AndroidX Activity Compose, AndroidX Core (PackageManager). No new third-party libraries.
**Storage**: Existing `LockedAppsRepository` (Room) for lock state; existing `EncryptedPrefsMasterKeyStore` for `MasterKey`. No new Room migrations.
**Testing**: JUnit4 + MockK + Turbine for ViewModels; Compose UI Test for screens; existing `KryptTestRunner` Hilt instrumented runner.
**Target Platform**: Android 10--15 phones (matches feature 001).
**Project Type**: Single Android module (`:app`).
**Performance Goals**:
- Permission indicator update <500 ms after `onResume`.
- Home Screen first paint <1.5 s on a device with 50 installed apps.
- Search filter <100 ms for 200 apps (in-memory substring).
**Constraints**: Offline-only --- `INTERNET` permission remains absent (FR-005 from feature 001). Manifest audit must continue to pass.
**Scale/Scope**: One module, ~12 new Kotlin files, ~3 modified files, ~8 new tests. Feature is UI-heavy with thin business logic.

## Constitution Check

Krypt's repo does not currently maintain a constitution.md, so no constitution gates apply. The feature must continue to honour the spec-level invariants of feature 001:

- **Air-gapped:** No new permission additions, no `INTERNET`. Verified by `verifyManifest` Gradle task (already wired).
- **Default-Deny:** Auto-lock-on-install (WP08 of feature 001) MUST remain active. New manual locking is *additive*.
- **No Subject-side unlock:** FR-018 from feature 001 remains absolute. The Home Screen toggle's "OFF attempted" path emits a request, never an unlock.

## Project Structure

### Documentation (this feature)

```
polaris-specs/002-manual-lock-and-permission-clarity/
├── plan.md              # This file
├── spec.md
├── control-map.md       # Two flows: Onboarding + Home --- see file
├── meta.json
├── checklists/
└── tasks/               # Populated by /polaris.tasks
```

### Source Code (repository root)

```
app/src/main/java/com/krypt/app/
├── permission/                     # NEW --- feature 002
│   ├── PermissionKey.kt            # enum { ACCESSIBILITY, OVERLAY, BATTERY, DEVICE_ADMIN, NOTIFICATIONS }
│   ├── PermissionClassification.kt # enum { MANDATORY, OPTIONAL }
│   ├── PermissionStatusProbe.kt    # interface + AndroidPermissionStatusProbe (queries OS state)
│   ├── PermissionStateObserver.kt  # combines onResume polling + listeners; emits StateFlow<Map<key, Boolean>>
│   └── PermissionIndicator.kt      # Compose component: animated ✓/✗
├── ui/onboarding/                  # MODIFIED
│   ├── OnboardingScreen.kt         # rewired: mandatory bar, skip buttons on optional steps
│   ├── OnboardingViewModel.kt      # NEW --- drives step navigation, gates Finish on 3/3 Mandatory
│   └── OnboardingStep.kt           # NEW --- sealed type per step; carries classification + key
├── ui/home/                        # NEW --- feature 002
│   ├── HomeScreen.kt               # searchable list of installed apps with toggles
│   ├── HomeViewModel.kt            # exposes filtered list + handles toggle taps
│   ├── InstalledAppsRepository.kt  # PackageManager wrapper; filters system apps
│   └── InstalledAppRow.kt          # row composable (icon + name + toggle)
└── ui/main/MainActivity.kt         # MODIFIED --- point post-onboarding navigation at HomeScreen

app/src/test/java/com/krypt/app/
├── permission/
│   ├── PermissionStatusProbeTest.kt
│   ├── PermissionStateObserverTest.kt
│   └── PermissionIndicatorTest.kt        # Compose UI test (Robolectric)
├── ui/onboarding/
│   ├── OnboardingViewModelTest.kt
│   └── OnboardingScreenTest.kt
└── ui/home/
    ├── InstalledAppsRepositoryTest.kt
    └── HomeViewModelTest.kt

app/src/androidTest/java/com/krypt/app/e2e/
└── ManualLockAndPermissionE2ETest.kt    # device-resident e2e for the toggle + Share sheet flow

tests/002-manual-lock-and-permission-clarity/
└── manual-lock-and-permission-clarity-suite.spec.js   # polaris feature-scoped regression entry point
```

**Structure Decision**: Single Android module, two new packages (`permission/`, `ui/home/`), and surgical modifications to `ui/onboarding/` and `ui/main/MainActivity.kt`. Test files mirror the production package layout.

## Complexity Tracking

No constitution violations to track. The complexity is contained:

- No new third-party dependencies.
- No Room migrations (the existing `LockedAppEntity` table covers all manual-lock state).
- No new permissions in `AndroidManifest.xml` (the manifest already declares `QUERY_ALL_PACKAGES` for the existing auto-locker; feature 002 reads the same source).
- The "icon resolution at scale" risk is mitigated by lazy-loading via Compose's `LaunchedEffect` per row plus an in-memory LRU cache keyed by package name.

## Phase 0 --- Research (no unknowns)

The implementation reuses well-understood Android primitives:
- `AccessibilityManager.isEnabled` + `getEnabledAccessibilityServiceList` to detect Krypt's accessibility service.
- `Settings.canDrawOverlays(context)` for Overlay permission.
- `PowerManager.isIgnoringBatteryOptimizations(packageName)` for the battery-optimisation grant.
- `DevicePolicyManager.isAdminActive(componentName)` for Device Admin.
- `NotificationManagerCompat.from(context).areNotificationsEnabled()` for `POST_NOTIFICATIONS`.
- `PackageManager.getInstalledApplications(0)` filtered by `(flags and ApplicationInfo.FLAG_SYSTEM) == 0`, then `applicationInfo.loadLabel(pm)` and `loadIcon(pm)`.

No `research.md` is needed --- every API is in `androidx.*` or framework `android.*`. Cross-referencing feature 001's research notes (R1--R5) covers the deeper crypto questions, none of which are touched here.

## Phase 1 --- Design

### Data model

**No new persisted entities.** The two new in-memory data shapes are:

```kotlin
data class PermissionSnapshot(
    val key: PermissionKey,
    val classification: PermissionClassification,
    val granted: Boolean,
    val observedAtMs: Long,
)

data class InstalledAppRow(
    val packageName: String,
    val displayName: String,
    val iconLoader: () -> Drawable, // deferred PackageManager call
    val isLocked: Boolean,
)
```

`InstalledAppRow.isLocked` is derived per-render from `LockedAppsRepository.allLockedFlow()` (existing). The Home Screen view model joins the PackageManager list with the locked-set and emits a `StateFlow<List<InstalledAppRow>>` filtered by the current search query.

### Contracts

No HTTP/RPC contracts. The internal contracts are Kotlin interfaces:

- `PermissionStatusProbe.statusOf(key: PermissionKey): Boolean` --- queries OS state, no caching.
- `PermissionStateObserver.observe(): StateFlow<Map<PermissionKey, Boolean>>` --- emits each `onResume` plus pushed events when supported (e.g., `AccessibilityManager.AccessibilityStateChangeListener`).
- `InstalledAppsRepository.allInstalled(): List<InstalledAppMeta>` --- synchronous on `Dispatchers.IO`, sorted by display name.

### Quickstart

Reviewer steps:

1. Pull `main`, `./gradlew :app:installDebug`.
2. Open Krypt on a device fresh-installed (or after Clear Data). Onboarding shows three Mandatory cards each with ✗ red and a Skip-disabled "Open settings" CTA. The two Optional cards (Device Admin, Notifications) show a visible Skip button.
3. Grant Accessibility from Settings, return --- indicator transitions to ✓ green via cross-fade. Mandatory bar advances 1/3.
4. Repeat for Overlay and Battery. At 3/3, "Finish" enables.
5. Skip the two Optional steps. Onboarding ends, app routes to the new Home Screen.
6. Home Screen shows every installed non-system app. Auto-locked apps are pre-toggled ON.
7. Type into the search bar --- filtering is instant.
8. Tap an unlocked toggle --- it animates to ON; relaunching that app shows the Locker Screen.
9. Tap a locked toggle --- toggle does NOT change state; the system Share sheet opens with a `krypt://request?...` URL whose body parses cleanly through `UnlockRequestParser`.

### Architectural decisions

- **One-way toggle** is a UI invariant, not a state-machine: the toggle's `onCheckedChange` listener is gated on the current locked state. If `isLocked == true`, we ignore the OS toggle change attempt, restore the toggle's bound state, and dispatch `RequestUnlockShareIntent`. If `isLocked == false`, we run `LockedAppsRepository.lock(pkg)` and let the StateFlow re-emit the new state.
- **PermissionStateObserver** uses a hot `StateFlow` scoped to the Activity's lifecycle. On `onResume` it re-runs every `PermissionStatusProbe.statusOf` and emits an updated map. Where supported (Accessibility), it also subscribes to system listeners for sub-second updates.
- **Icon loading** uses Compose's `produceState` per row; the bitmap is held in a singleton `LruCache(maxBytes = 16 MB)` keyed by package name. Eviction is safe because we can re-resolve from PackageManager on demand.
- **Auto-lock parity:** the existing `PackageReceiver`/`LockedAppsAutoLockUseCase` from feature 001 is unchanged. Manual locking is a parallel write path into the same repository.

## Phase 2 --- Tasks

Out of scope for plan.md. `/polaris.tasks` will derive WP01..WPnn from this plan.
