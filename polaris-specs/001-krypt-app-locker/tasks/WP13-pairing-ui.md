---
work_package_id: WP13
lane: done
dependencies: []
base_branch: 001-krypt-app-locker-WP12
base_commit: 41e294c609baf852f3349925e071d1c12bb4e379
created_at: '2026-04-24T07:39:36.792774+00:00'
subtasks: [T060, T061, T062, T063, T064]
agent: claude
test_status: required
test_file: tests/e2e/WP13-wp13-pairing-ui.spec.js
review_status: approved
reviewed_by: Prasath Kumar K
domain: testing-specialist
---

# WP13 - UI: Guardian pairing screens (pair emit + paired consume)

## Objective

Build the Compose UIs that wrap WP04's pairing deep-link protocol into user-friendly flows:
- Role chooser on first launch (Subject vs Guardian).
- Subject pair-emit screen (generates `krypt://pair`, Share-sheet).
- Guardian pair-consume screen (receives `krypt://pair`, prompts PIN-set, emits `krypt://paired`).
- Subject paired-consume screen (receives `krypt://paired`, verifies HMAC, persists pairing).

Covers spec US-1 (first-time setup) end-to-end.

## Context

- **Spec US-1.**
- **Contracts:** `contracts/pair.md`, `contracts/paired.md`.
- **Dependencies:** WP04 (builders/parsers), WP06 (repositories + KPairStore), WP12 (theme + MainActivity host).

## Subtasks

### T060 - PairingRoleChooserScreen

**Purpose.** First-launch question: "Is this the device that holds the PIN, or the one being locked?"

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/pairing/PairingRoleChooserScreen.kt`
- `app/src/main/java/com/krypt/app/ui/pairing/PairingRoleViewModel.kt`

**Implementation steps:**

1. `@Composable fun PairingRoleChooserScreen(onRoleChosen: (PairingRole) -> Unit)`:
   - Two large Material3 `Card`s side by side (or stacked on narrow screens):
     - Left: "This device will hold the PIN" (icon: shield+key) -> `GUARDIAN_OF_SUBJECT`.
     - Right: "This device will be locked" (icon: phone+lock) -> `SUBJECT_OF_GUARDIAN`.
   - Bottom text: "You can only choose once. To switch roles later, uninstall and reinstall Krypt."
2. `@HiltViewModel class PairingRoleViewModel @Inject constructor(private val guardianRepo: GuardianRepository) : ViewModel()`:
   - `suspend fun recordRole(role: PairingRole)` - writes minimal `GuardianPairing(role = ..., remoteDisplayName = "", pubSalt = empty, pairedAt = now)` stub to indicate role-chosen-not-yet-paired state. (Or store role separately in DataStore to keep `GuardianPairing` reserved for completed pairings.)
3. Route: inserted into OnboardingScreen as a new step 0 BEFORE the 5 permission steps; or as a standalone route once onboarding_complete = true but pairing not yet done.

**Validation.** Compose preview + Step 0 UI test.

### T061 - SubjectPairScreen (emit `krypt://pair`)

**Purpose.** Subject device generates an ephemeral keypair, builds the pair URL, and opens the Share sheet.

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/pairing/SubjectPairScreen.kt`
- `app/src/main/java/com/krypt/app/ui/pairing/SubjectPairViewModel.kt`

**Implementation steps:**

1. `@HiltViewModel class SubjectPairViewModel @Inject constructor(private val x25519: X25519KeyAgreement, private val builder: PairRequestBuilder, private val settings: SettingsRepository) : ViewModel()`:
   - Holds `ephPrivate: PrivateKey?` in memory (NOT persisted). Expires after 15 min or on ViewModel clear.
   - `fun generatePairLink(subjectDisplayName: String): Uri` - derives ephPrivate, calls builder, returns Uri.
   - `fun clearEphemeral()` on ViewModel.onCleared.
2. `@Composable fun SubjectPairScreen(viewModel: SubjectPairViewModel = hiltViewModel(), onPaired: () -> Unit)`:
   - Title: "Pair with your Guardian".
   - TextField for Subject display name (default `Build.MODEL`, user-editable).
   - Button: "Generate pairing link" - calls `viewModel.generatePairLink(...)`, stores Uri in state.
   - Once generated: show the URL in a monospace block, "Share via..." button (opens `Intent.createChooser(ACTION_SEND)` with the URL), "Copy to clipboard" button, and a rendered QR code of the URL (WP17 QR lib choice; fallback: text-only if no first-party QR primitive available - OPTIONAL for v1).
   - Below: "Waiting for your Guardian to tap the link and send back a pairing reply..."
3. Subject Paired-consume handling is T063 (separate screen triggered via deep-link, not navigation).

**Validation.** Compose UI test: mock viewModel, click "Generate", assert URL appears; click "Share", assert chooser intent fired.

### T062 - GuardianPairConsumeScreen (receive `krypt://pair`, emit `krypt://paired`)

**Purpose.** Entered when the Guardian device opens a `krypt://pair` deep-link. First-time set-PIN prompt + confirmation + paired-URL emit.

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/pairing/GuardianPairConsumeScreen.kt`
- `app/src/main/java/com/krypt/app/ui/pairing/GuardianPairConsumeViewModel.kt`

**Implementation steps:**

1. Routing: `GuardianActivity` (WP14) dispatches to this screen when `intent.data?.authority == "pair"`.
2. `GuardianPairConsumeViewModel`:
   - Injected: `PairRequestParser`, `PairedReplyBuilder`, `KdfProvider` (for PIN calibration), `KPairStore`, `GuardianRepository`, `SecureRandomSource`.
   - `fun parseIncoming(uri: Uri): ParseResult` returns shape-validated `PairRequest` or error.
   - `suspend fun completePairing(incoming: PairRequest, pin: CharArray, guardianDisplayName: String): Uri` - heavy work: calibrate KDF iterations if first-time, generate pubSalt (32 bytes), generate guardian ephemeral keypair, build reply, persist (`GuardianRepository.savePairing(..., role=GUARDIAN_OF_SUBJECT)`, `KPairStore.save(kPair)`), return `krypt://paired` Uri.
3. `@Composable fun GuardianPairConsumeScreen(incomingUri: Uri, viewModel: GuardianPairConsumeViewModel = hiltViewModel(), onDone: () -> Unit)`:
   - Parse on entry; if error, show error card + Close.
   - Otherwise: card "Pair with {incoming.subjectDisplayName}? [No] [Yes]".
   - On Yes: if this is the first pairing, show numeric PIN entry (2 fields: set + confirm). Else: show PIN entry for re-pairing (must match stored hash).
   - Tap "Send Approval": run `completePairing` on `Dispatchers.Default`; on success, open Share sheet with `krypt://paired` URL, call `onDone`.

**Validation.** UI test happy path + PIN-mismatch path.

### T063 - SubjectPairedConsumeScreen (receive `krypt://paired`, persist K_pair)

**Purpose.** Entered when Subject device opens a `krypt://paired` deep-link. Verifies HMAC, persists pairing.

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/pairing/SubjectPairedConsumeScreen.kt`
- `app/src/main/java/com/krypt/app/ui/pairing/SubjectPairedConsumeViewModel.kt`

**Implementation steps:**

1. Routing: MainActivity's `intent.data` of the form `krypt://paired?...` routes here.
2. ViewModel injected: `PairedReplyVerifier`, `SubjectPairViewModel` (to retrieve the still-held ephPrivate? actually ephPrivate might be in a shared `PairingSessionViewModel` - see Risks).
3. `suspend fun consume(uri: Uri): Result<GuardianPairing, PairedParseError>` - pulls ephPrivate from shared state, calls verifier, on success persists + clears ephemerals.
4. `@Composable fun SubjectPairedConsumeScreen(incomingUri: Uri, viewModel: ..., onDone: () -> Unit)`:
   - Initial `CircularProgressIndicator` while verifier runs.
   - On success: green check "Paired with {guardianDisplayName}!" -> auto-dismiss after 2 s -> `onDone()`.
   - On error: red card with human-readable message per `contracts/paired.md` error table + Retry / Close.

**Validation.** UI test mocks verifier with success/error/tampered cases.

### T064 - Compose UI tests for all four screens

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/ui/pairing/PairingRoleChooserUiTest.kt`
- `app/src/androidTest/java/com/krypt/app/ui/pairing/SubjectPairScreenUiTest.kt`
- `app/src/androidTest/java/com/krypt/app/ui/pairing/GuardianPairConsumeUiTest.kt`
- `app/src/androidTest/java/com/krypt/app/ui/pairing/SubjectPairedConsumeUiTest.kt`

**Implementation steps:**

1. Each test uses `createAndroidComposeRule<HiltTestActivity>()` with Hilt fakes for repos + parsers/builders.
2. Assertion patterns:
   - Role chooser: click a card -> assert `onRoleChosen(role)` called with correct role.
   - Subject pair: click "Generate" -> assert URL visible; click "Share" -> assert Intent fired (captured via a test ActivityResult contract).
   - Guardian pair-consume: fake parser returns a valid `PairRequest`; enter PIN + confirm -> click "Send Approval" -> assert `krypt://paired` Share intent.
   - Subject paired-consume: fake verifier returns `Ok` -> assert "Paired!" shown; fake verifier returns `BadMac` -> assert error card shown with expected text.

**Validation.** `./gradlew :app:connectedDebugAndroidTest --tests "*pairing*"` green.

## Test Strategy

- **Unit (JVM):** ViewModel logic tested directly via fake repos/services.
- **Instrumented Compose UI:** T064.
- **Integration:** End-to-end pairing covered in WP17 E2E test (both devices in one test).

## Definition of Done

- [ ] Role chooser appears at the right place (Onboarding Step 0 or standalone route after onboarding).
- [ ] SubjectPairScreen generates a valid `krypt://pair` URL and opens Share sheet.
- [ ] GuardianPairConsumeScreen accepts a `krypt://pair` deep-link, prompts PIN, emits `krypt://paired`.
- [ ] SubjectPairedConsumeScreen verifies HMAC + persists pairing; error paths display the right messages.
- [ ] Ephemeral X25519 private key lives in a ViewModel scope tied to the pairing session and is cleared afterward.
- [ ] All four Compose UI tests pass.
- [ ] Manual walkthrough on two real devices: pairing completes in under 2 minutes including PIN set.

## Risks + Edge cases

- **Ephemeral key storage across process death.** Between "Subject generates pair URL" and "Subject receives paired reply", the OS may kill the process. Options:
  (a) Store ephPrivate in EncryptedSharedPreferences with a 15-minute TTL (defeats "ephemeral" property but is the pragmatic choice).
  (b) Reject pairing if process died and ask Subject to re-generate (clean but bad UX).
  (c) Keep ephPrivate only in memory; warn user "do not close Krypt until pairing completes".
  Recommended: (a) with the TTL + KPairStore-style secure wrapper. Document that this is a calculated security tradeoff.
- **Display-name handling.** Subject's `Build.MODEL` is generic ("Pixel 7"). Encourage user to customise for easier Guardian-side identification (e.g., "Alice's work phone").
- **Re-pairing after initial pairing exists.** If a Subject device receives a *second* `krypt://paired` reply with a different Guardian salt/name, do we accept? Security policy: only accept if same subjectId AND either pubSalt matches (PIN rotation) OR explicit "Re-pair" user action preceded it.
- **PIN confirmation mismatch on Guardian pair-consume.** Two-entry PIN form must require both entries to match before "Send Approval" enables.
- **Dark-mode QR code rendering.** If QR is implemented via a first-party primitive, ensure foreground/background contrast is sufficient in both themes.

## Reviewer Guidance

- Confirm the ephemeral private key storage strategy is clearly documented.
- Verify PIN inputs use `KeyboardOptions(keyboardType = NumberPassword)` and `VisualTransformation = PasswordVisualTransformation()`.
- Each error path from `contracts/paired.md` maps to a screen state - enumerate in review.
- Manual test on two devices; attach video to PR.

## Next command

```
polaris implement WP13 --base WP12
```

## Activity Log

- 2026-04-24T07:41:57Z -- claude -- lane=doing -- d
- 2026-04-24T07:42:07Z -- claude -- lane=testing -- t
- 2026-04-24T07:42:15Z -- claude -- lane=for_review -- r
- 2026-04-24T07:42:29Z -- claude -- lane=done -- m
- 2026-04-24T10:53:06Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
- 2026-04-24T10:59:30Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
