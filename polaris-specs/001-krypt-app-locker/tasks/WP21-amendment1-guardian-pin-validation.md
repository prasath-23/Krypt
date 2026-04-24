---
work_package_id: WP21
lane: planned
dependencies: [WP19, WP20]
base_branch: main
created_at: '2026-04-24T11:10:00+00:00'
subtasks: [T110, T111, T112, T113]
test_status: required
test_file: tests/e2e/WP21-amendment1-guardian-pin-validation.spec.js
amendment: 1
domain: backend-logic
---

# WP21 - Amendment 1: Guardian-side PIN validation

## Objective

Rework `GuardianActivity` so the Guardian types the PIN, the app derives `MasterKey` from PIN + `salt` (extracted from the incoming request URL), computes `pinProof`, and compares in constant time to the `pinProof` embedded in the URL. On match, invoke `ApprovalLinkBuilder` (from WP20) to produce the approval URL; on mismatch, show a clear "wrong PIN" error with rate-limiting. Satisfies amended US-4.

## Context

- **Spec Amendment 1:** Section "What changed" bullet 4; FR-008 (unchanged).
- **Depends on:** `UnlockRequestParser` (WP20), `ApprovalLinkBuilder` (WP20), `KdfProvider + HmacProvider` (WP02, WP19).
- **No persistent state on the Guardian device.** This is a deliberate goal: any Android device that can open a `krypt://request` URL and has the Krypt APK installed can act as a Guardian if the person typing knows the PIN.

## Subtasks

### T110 - GuardianPinValidator (pure logic)

**Files to create:**
- `app/src/main/java/com/krypt/app/guardian/GuardianPinValidator.kt`

**Implementation:**
1. `class GuardianPinValidator @Inject constructor(kdf: KdfProvider, hmac: HmacProvider)`.
2. `suspend fun validate(pin: CharArray, request: UnlockRequest): Result<ByteArray, ValidationError>` on `Dispatchers.Default`. Returns the derived `MasterKey` on success so the caller can pass it to `ApprovalLinkBuilder`.
3. Steps:
   - `iters = 300_000` (floor; Guardian device may need calibration too - cache in DataStore keyed by device model if helpful).
   - `masterKey = kdf.derive(pin, request.salt, iters, 32)`.
   - `recomputedProof = hmac.sha256(masterKey, "krypt/v1/pin-proof".toByteArray())`.
   - `matches = MessageDigest.isEqual(recomputedProof, request.pinProof)` (constant-time).
   - Zero `pin` CharArray in `finally`.
   - If `!matches`, return `Err(ValidationError.WrongPin)`; zero `masterKey` before returning the Err.
4. `sealed interface ValidationError { object WrongPin : ValidationError; object Expired : ValidationError; data class TooManyAttempts(val retryAfterMs: Long) : ValidationError }`.

### T111 - GuardianActivity rework

**Files to modify:**
- `app/src/main/java/com/krypt/app/guardian/GuardianActivity.kt`
- `app/src/main/java/com/krypt/app/guardian/GuardianPinScreen.kt`
- `app/src/main/java/com/krypt/app/guardian/GuardianViewModel.kt`

**Implementation:**
1. `GuardianActivity` handles deep-link intents with `data.scheme=krypt` and `data.host=request`. Parses with `UnlockRequestParser`.
2. On parse success: shows `GuardianPinScreen` with target-package name and PIN field. On parse failure: shows friendly error matching `contracts/request.md` error handling table.
3. `GuardianPinScreen`: PIN entry (4-6 digits, numeric-password keyboard), "Approve" button. Shows the target app name prominently so the Guardian knows WHAT they're approving.
4. `GuardianViewModel`:
   - `suspend fun approve(pin: CharArray)`:
     - `validator.validate(pin, request)` -> masterKey or error.
     - On `WrongPin`: rate-limit counter (in-memory; max 3 attempts, then 60 s lockout). Show error.
     - On success: `approvalUri = approvalLinkBuilder.build(masterKey, request, grantDurationMinutes = 15)`. Zero `masterKey` after use. Fire `Intent.ACTION_SEND` with `approvalUri.toString()` text/plain to open the system share sheet. Leave activity open with a "sent" state.
5. Remove any legacy X25519 / KPairStore reads from `GuardianActivity` and `GuardianViewModel`.

### T112 - Rate-limit + WrongPin UX

**Implementation:**
1. Counter in `GuardianViewModel` (in-memory; acceptable because the attacker has to close-and-reopen the activity to evade).
2. Three wrong PIN attempts within 60 seconds -> 60-second lockout with countdown shown on screen. Counter clears on successful approve or after the lockout.
3. Copy: "Wrong PIN (2 of 3 attempts left)" then "Too many wrong PIN attempts. Try again in 42 s." The Guardian MUST NOT see WHY their PIN was wrong (no hint content).

### T113 - Unit tests

**Files to create:**
- `app/src/test/java/com/krypt/app/guardian/GuardianPinValidatorTest.kt` - known (`pin`, `salt`, `pinProof`) triple: correct PIN -> Ok; wrong PIN -> `WrongPin`; proof with tampered byte -> `WrongPin`. Assert `pin` CharArray is zeroed after call.
- `app/src/test/java/com/krypt/app/guardian/GuardianViewModelTest.kt` - MockK validator + approvalLinkBuilder. Rate-limit test: 3 wrong attempts -> lockout; 4th attempt blocked without calling KDF.

## Definition of Done

- [ ] Correct PIN + valid request -> `ApprovalLinkBuilder.build` invoked exactly once; approval URI produced; share sheet opens.
- [ ] Wrong PIN -> `WrongPin` error surfaced with "attempts left" counter; no approval URI created.
- [ ] 3 wrong attempts -> 60 s lockout; clock-based countdown.
- [ ] Constant-time proof compare (`MessageDigest.isEqual`).
- [ ] Typed `CharArray` zeroed after every validate call (verified in test).
- [ ] No writes to disk on the Guardian device (stateless).
- [ ] Expired request -> friendly error, no PIN prompt.

## Risks and Edge cases

- **Guardian device has different CPU than Subject.** The Guardian's 300k-iter PBKDF2 cost may be 100-800 ms depending on hardware. That's fine - it only runs when the Guardian types a PIN. Optional: calibrate once and store in DataStore keyed by `Build.MODEL`.
- **Guardian cache poisoning.** `MessageDigest.isEqual` is specifically designed to avoid timing leaks even when the comparator receives attacker-controlled data. Do NOT replace with `contentEquals` "for readability".
- **Share sheet fallback.** On some OEMs, `ACTION_SEND` may not offer WhatsApp. Provide a "copy link" button as secondary path; clipboard content labelled "Approval link (ephemeral - share immediately)".
- **GuardianActivity spoof.** A malicious package could declare its own `krypt://request` intent-filter and steal the URL. Krypt's manifest should set `android:exported=true` only for `GuardianActivity` with `android:launchMode="singleInstance"`. Consider `android:autoVerify="true"` + custom scheme verification (nothing enforceable without App Links over https, so accept the threat per v1 threat model).
