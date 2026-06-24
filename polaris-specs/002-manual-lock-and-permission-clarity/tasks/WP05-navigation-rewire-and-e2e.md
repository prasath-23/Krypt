---
work_package_id: WP05
lane: "done"
review_status: approved
reviewed_by: Prasath Kumar K
dependencies: [WP03, WP04]
base_branch: main
subtasks: [T025, T026, T027, T028, T029]
test_status: required
test_file: tests/002-manual-lock-and-permission-clarity/WP05-navigation-rewire-and-e2e.spec.js
domain: testing-specialist
---

# WP05 - Navigation rewire + integration e2e

## Objective

Wire the new `HomeScreen` into Krypt's post-onboarding navigation, and add device-resident e2e tests covering the three high-stakes flows: mandatory permission gate, manual lock, and attempted-unlock share-sheet dispatch.

## Context

- **Spec:** ties together FR-027, FR-030, FR-032, FR-033. SC-009 (mandatory progress timing), SC-011 (lock latency), SC-012 (URL parses).
- **Depends on WP03 (onboarding) and WP04 (home).** Both produce screens; this WP joins them.
- **Existing routing:** `MainActivity` (feature 001 WP12 + Amendment 1 WP19) maintains an `AppScreen` enum and a `MainRoute` Composable that selects which screen to render based on (a) `MasterKeyStore.isConfigured()` and (b) `settings.onboardingComplete`. We add a new branch: when both are true, route to `HomeScreen`.

## Subtasks

### T025 --- `MainActivity` / `MainRoute` rewire

**Files to modify:**
- `app/src/main/java/com/krypt/app/ui/main/MainActivity.kt`
- `app/src/main/java/com/krypt/app/ui/main/MainViewModel.kt`

**Implementation:**
1. Extend the existing `AppScreen` enum: add `HOME` (the new manual-lock screen). Keep the existing values (`PIN_SETUP`, `ONBOARDING`, etc.).
2. In `MainViewModel`, the resolution logic becomes:
   - `!masterKeyConfigured` → `PIN_SETUP`
   - `!onboardingComplete` → `ONBOARDING`
   - `else` → `HOME` (was previously the legacy placeholder).
3. In `MainRoute`, add the new `AppScreen.HOME -> HomeScreen(...)` branch. Pass the `AppIconCache` (Hilt singleton) into the screen.
4. Remove or hide any stale references to the legacy placeholder Home from feature 001 WP12. Do NOT delete the file outright if it has reusable subcomponents --- just stop routing to it.

### T026 --- Share-intent dispatcher wiring

**Files to modify:**
- `app/src/main/java/com/krypt/app/ui/home/HomeScreen.kt` (already started in WP04 T021).

**Implementation:**
1. Confirm `LaunchedEffect(Unit) { viewModel.shareIntents.consumeAsFlow().collect { intent -> context.startActivity(Intent.createChooser(intent, ...)) } }` is wired.
2. Confirm the chooser title comes from `R.string.home_share_chooser_title` (added in WP04 T021).
3. Add a sibling `Channel<UserMessage>` collector for the `MasterKey not configured` error case --- emit a `Snackbar` via the screen's `SnackbarHostState`.

### T027 --- `OnboardingMandatoryGateE2ETest` (androidTest)

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/e2e/OnboardingMandatoryGateE2ETest.kt`

**Coverage:**
1. `@HiltAndroidTest` with the existing `KryptTestRunner`.
2. `@TestInstallIn` a fake `PermissionStateObserver` that exposes a writeable `MutableStateFlow`.
3. Launch `MainActivity` post-PIN-setup so we land on `OnboardingScreen`.
4. Initial state: `{ all=false }` → assert all five step cards (driven across navigation) show ✗ red, mandatory progress reads `0/3`.
5. Push `{ ACCESSIBILITY=true }` → swipe / Next to step 1 → assert ✓ green, progress `1/3`.
6. Push `{ ACCESSIBILITY=true, OVERLAY=true }` → progress `2/3`.
7. Reach Optional Notifications step → assert "Skip" button visible.
8. Push `{ ACCESSIBILITY=true, OVERLAY=true, BATTERY=true }` → reach last step → assert "Finish" enabled.
9. Push `{ ACCESSIBILITY=true, OVERLAY=true, BATTERY=false }` → assert "Finish" disabled and Battery indicator is ✗ red.
10. Tolerance: each `assertIsDisplayed` runs after `composeTestRule.mainClock.advanceTimeBy(500)` to absorb the cross-fade.

### T028 --- `ManualLockToggleE2ETest` (androidTest)

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/e2e/ManualLockToggleE2ETest.kt`

**Coverage:**
1. Pre-condition setup: PIN configured, onboarding complete, `LockedAppsRepository` is empty.
2. `@TestInstallIn` a fake `InstalledAppsRepository` returning a curated 3-element list `[com.example.alpha, com.example.beta, com.example.gamma]` with stable display names.
3. Launch `MainActivity` → assert routed to `HomeScreen`.
4. Find row "Alpha" (currently unlocked) → click its `Switch` → assert `lockedRepo.isLocked("com.example.alpha")` is true within 200 ms.
5. Verify the row's `Switch` is now `assertIsOn()`.
6. The "covered next launch" assertion is integration-level and depends on overlay services; for v1 we assert ONLY the persistence + UI state. The overlay-coverage e2e is already covered by feature 001 WP23 and remains untouched.

### T029 --- `AttemptedUnlockShareSheetE2ETest` (androidTest)

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/e2e/AttemptedUnlockShareSheetE2ETest.kt`

**Coverage:**
1. Pre-condition: PIN configured, `lockedRepo` already contains `com.example.beta`.
2. Use Espresso `Intents` machinery: `Intents.init()` in `@Before`, `Intents.release()` in `@After`.
3. `Intents.intending(hasAction(Intent.ACTION_CHOOSER))` returns a stub result --- we don't actually pop the share sheet on CI emulators.
4. Launch `HomeScreen`. Click the Switch on row "Beta" (currently locked).
5. Assert:
   - The `Switch` did NOT change (still ON).
   - An `ACTION_SEND` intent was created --- extract via the captured `Intents.intended(allOf(hasAction(ACTION_SEND), hasExtraWithKey(EXTRA_TEXT)))`.
   - The captured `EXTRA_TEXT` parses through `UnlockRequestParser.parse(text, nowSeconds=now)` to `Outcome.Ok` with `targetPackage == "com.example.beta"`.
6. Confirm `lockedRepo.isLocked("com.example.beta")` is still `true`. Subject did not unlock.

## Definition of Done

- [ ] Post-onboarding navigation lands on `HomeScreen` (not the legacy placeholder).
- [ ] `OnboardingMandatoryGateE2ETest` is green on emulator API 33 and 35.
- [ ] `ManualLockToggleE2ETest` is green; row Switch state matches `LockedAppsRepository.isLocked` after tap.
- [ ] `AttemptedUnlockShareSheetE2ETest` is green; the captured intent's URL parses through `UnlockRequestParser` and the lock state remains unchanged.
- [ ] `./gradlew :app:connectedDebugAndroidTest` passes when run on an emulator with the existing feature-001 androidTest suite (no regressions).
- [ ] Total e2e suite runtime budget: <90 s on a standard emulator (re-use the WP23 KDF-iteration `@TestInstallIn` to keep PBKDF2 cheap in tests).

## Risks and Edge cases

- **Real `PackageManager` listing.** Even with a fake repo, the device's actual installed apps may leak into the Home Screen if the fake binding isn't applied correctly. Verify via the test's `Hilt` graph that only the fake provides `InstalledAppsRepository`.
- **`Intents.intending` ordering.** Set up `intending` BEFORE the action that triggers the intent, otherwise Espresso captures nothing. Place the `intending` block in a `@Before` of the relevant test.
- **Share sheet lifecycle on Android 14+.** The new chooser uses a `ChooserAction` API. We don't drive the chooser itself in the e2e --- we only assert that `Intent.createChooser` was started.
- **Hilt `@TestInstallIn` overrides.** Reuse the pattern from feature 001 WP23 `Amendment1TestModule`. Add a sibling `ManualLockTestModule` that overrides `InstalledAppsRepository` and `PermissionStateObserver` for the e2e tests only.

## Reviewer guidance

- Run `./gradlew :app:connectedDebugAndroidTest` on an emulator and verify the three new e2e tests are green.
- Manual smoke: install the debug APK on a real device, complete onboarding, then add WhatsApp to the locked list --- confirm the Locker Screen appears on the next launch.
- Manual smoke 2: tap the WhatsApp toggle off --- confirm the share sheet opens with the URL, and the toggle stays ON.
- Implement command: `polaris implement WP05 --base WP04`.

## Activity Log

- 2026-04-25T06:24:23Z – unknown – lane=doing – Starting WP05 on branch 002-manual-lock-and-permission-clarity-WP05
- 2026-04-25T06:26:49Z – unknown – lane=for_review – WP05: navigation wired (MainRoute already correct), ManualLockTestModule, 3 e2e test files. Commit 9df8fac.
