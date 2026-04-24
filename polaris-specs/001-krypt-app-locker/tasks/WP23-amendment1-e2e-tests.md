---
work_package_id: WP23
lane: planned
dependencies: []
base_branch: main
created_at: '2026-04-24T11:10:00+00:00'
subtasks: [T119, T120, T121, T122]
test_status: required
test_file: tests/e2e/WP23-amendment1-e2e-tests.spec.js
amendment: 1
domain: testing-specialist
---

# WP23 - Amendment 1: End-to-end tests for silent-unlock flow

## Objective

Exercise the full Amendment 1 happy-path and each negative branch with instrumented Android tests. Confirms that the integrated stack (setup + request + Guardian approve + silent Subject consume) behaves per Amendment 1 spec.

## Context

- **Depends on:** WP19, WP20, WP21, WP22 code complete.
- **Runner:** AndroidX Test (JUnit4) + Compose UI Test + `ActivityScenario`. Runs under `./gradlew :app:connectedDebugAndroidTest`.
- **Fakes:** JVM-safe fakes where practical; for storage (EncryptedSharedPreferences) and AccessibilityService integration, use on-device instrumented tests.

## Subtasks

### T119 - Happy-path e2e (single device, both sides in-process)

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/e2e/Amendment1HappyPathTest.kt`

**Scenario:**
1. Launch app, run `PinSetupScreen`, type PIN "8888", Save.
2. Simulate request issuance: programmatically build `krypt://request?...` via `UnlockRequestBuilder` (same process, reads `MasterKeyStore`).
3. Hand the URL to `GuardianActivity` (`Intents.intending`), type PIN "8888", tap Approve.
4. Capture the outbound `ACTION_SEND` intent, extract the approval URL.
5. Launch `ApprovalTrampolineActivity` with the approval URL.
6. Assert: overlay session grant inserted; toast "Unlocked ..." visible; grant's `expiresAt` ~= now + 15 min; OutstandingRequest row has `consumed=true`.

### T120 - Wrong PIN on Guardian side

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/e2e/Amendment1WrongPinTest.kt`

**Scenario:**
1. Setup PIN "8888".
2. Build a request URL.
3. GuardianActivity: type "0000" -> error "Wrong PIN (2 of 3)". Type "1111" -> "Wrong PIN (1 of 3)". Type "2222" -> "Too many wrong PIN attempts. Try again in 60 s".
4. Assert no `ACTION_SEND` intent was fired.

### T121 - Replay / TTL / single-use

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/e2e/Amendment1ReplayTest.kt`

**Scenarios:**
1. Build request with `iat = now - 400` seconds -> consuming the resulting approval yields `RequestExpired`.
2. Build + consume once successfully; attempt to consume the same approval URL again -> `UnmatchedRequest`.
3. Fire two parallel `ApprovalTrampolineActivity` launches (via `ActivityScenario` + coroutines) with the same URL; exactly one succeeds, one shows the `UnmatchedRequest` toast.

### T122 - No-PIN-keypad on Subject side (FR-018 guard)

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/e2e/Amendment1NoKeypadTest.kt`

**Scenarios:**
1. Launch `ApprovalTrampolineActivity` with a valid approval URL. Use `onView(withClassName(endsWith("EditText"))).check(doesNotExist())` and `onView(hasContentDescription(containsString("PIN")))... doesNotExist()`.
2. Repeat with an INVALID approval URL. Same assertion.
3. These are hard gates: if they fail, the build fails.

## Definition of Done

- [ ] Happy-path test green on emulator API 33 + API 35.
- [ ] All three negative tests green.
- [ ] No-keypad assertion is part of `./gradlew connectedDebugAndroidTest` and CI-failing on regression.
- [ ] Test runtime budget: total <=120 s on a standard emulator (KDF calibration dominates; mock the KDF calibrator in tests to use a fixed low-iter count like 10_000, declared via a test-only `@BindValue`).

## Risks and Edge cases

- **KDF slowness in tests.** 300k PBKDF2 iters x several per test = tens of seconds of waste. Use a Hilt `@TestInstallIn` module to replace `KdfProvider` with a version that honours `iterations` literally but takes a smaller floor in tests. KEEP production default unchanged.
- **Clipboard / share sheet in instrumented tests.** Use `Intents.intended(hasAction(ACTION_SEND))` and intercept - do NOT attempt to drive WhatsApp itself.
- **Robolectric vs connected tests.** FR-018 (no input field) is asserted in Robolectric unit tests (WP22/T118) for fast feedback AND in the androidTest suite here for on-device guarantee.
