---
work_package_id: WP14
lane: "doing"
dependencies: [WP03, WP04, WP06, WP12]
base_branch: 001-krypt-app-locker-WP13
base_commit: 9039a3f41d06f5788d2278eb85e46be69a9c7064
created_at: '2026-04-24T07:43:12.396219+00:00'
subtasks: [T065, T066, T067, T068, T069, T070]
test_status: required
test_file: tests/e2e/WP14-wp14-guardian-activity.spec.js
shell_pid: "35364"
---

# WP14 - UI: GuardianActivity (request-consume + PIN UI + approval-emit)

## Objective

Build the Guardian-side Activity that handles incoming `krypt://request` URLs: shows target app, prompts for PIN, verifies PIN locally, and emits `krypt://approve`. Also handles incoming `krypt://approve` on Subject device (dispatch-only here; consumption in WP15). This Activity IS the one whitelisted from self-interception (FR-011).

## Context

- **Spec US-4, FR-011.**
- **Contracts:** `contracts/request.md`, `contracts/approve.md`.
- **Dependencies:** WP03 (request parser + approval builder), WP04 (pairing + KPairStore), WP06 (repos), WP12 (theme).
- **Whitelist contract:** `AppLockerAccessibilityService.GUARDIAN_ACTIVITY_CLASS` = `"com.krypt.app.ui.guardian.GuardianActivity"` - enforced in T069.

## Subtasks

### T065 - GuardianActivity host + intent-filter

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/guardian/GuardianActivity.kt`

**Files to modify:**
- `app/src/main/AndroidManifest.xml`

**Implementation steps:**

1. `@AndroidEntryPoint class GuardianActivity : ComponentActivity()`:
   - `override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(...); val data = intent.data; setContent { KryptTheme { GuardianRoute(data) } } }`.
   - Handles re-entry on `onNewIntent` for `singleTask` launchMode.
2. Manifest:
   ```xml
   <activity android:name=".ui.guardian.GuardianActivity"
             android:exported="true"
             android:launchMode="singleTask"
             android:excludeFromRecents="true"
             android:taskAffinity="">
     <intent-filter android:autoVerify="false">
       <action android:name="android.intent.action.VIEW" />
       <category android:name="android.intent.category.DEFAULT" />
       <category android:name="android.intent.category.BROWSABLE" />
       <data android:scheme="krypt" android:host="request" />
       <data android:scheme="krypt" android:host="approve" />
       <data android:scheme="krypt" android:host="paired" />
     </intent-filter>
   </activity>
   ```
3. `excludeFromRecents=true` so the Guardian UI doesn't linger in Recents after an approval (contains sensitive data-adjacent info).
4. Root decor has `FLAG_SECURE` applied in `onCreate` (prevents screenshots of PIN entry).

**Validation.** `adb shell am start -a android.intent.action.VIEW -d "krypt://request?v=1&req=..."` launches `GuardianActivity`.

### T066 - Route dispatch by URI authority

**Implementation steps:**

1. `@Composable fun GuardianRoute(data: Uri?)`:
   - If `data` is null -> show "Open Krypt from your messenger" fallback + Close.
   - Else switch on `data.authority`:
     - `"request"` -> `GuardianRequestScreen(data)`.
     - `"approve"` -> `GuardianApprovalConsumeScreen(data)` (WP15 defines the screen).
     - `"paired"` -> `SubjectPairedConsumeScreen(data)` (WP13).
     - `"pair"` -> `GuardianPairConsumeScreen(data)` (WP13).
     - else -> "Unknown deep-link" error card.

**Validation.** Unit test the dispatch function (pure).

### T067 - GuardianRequestScreen (PIN UI + approval emit)

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/guardian/GuardianRequestScreen.kt`
- `app/src/main/java/com/krypt/app/ui/guardian/GuardianRequestViewModel.kt`

**Implementation steps:**

1. `@HiltViewModel class GuardianRequestViewModel @Inject constructor(private val parser: UnlockRequestParser, private val approvalBuilder: ApprovalLinkBuilder, private val kpairStore: KPairStore, private val guardianRepo: GuardianRepository, private val kdf: KdfProvider, private val rateLimiter: RateLimiter, private val pm: PackageManager) : ViewModel()`:
   - `fun parseIncoming(uri: Uri): Result<UnlockRequest, RequestParseError>`.
   - `suspend fun verifyPinAndBuildApproval(request: UnlockRequest, pin: CharArray): Result<Uri, GuardianError>` on `Dispatchers.Default`:
     1. Check rate-limiter (T068) - if locked out, return `RateLimited(retryAtMs)`.
     2. Compute `derivedHash = kdf.derive(pin, guardianPubSalt, iterations, 32)`.
     3. Compare with stored `storedPinHash` using `MessageDigest.isEqual`.
     4. If mismatch: rateLimiter.recordFailure(); return `WrongPin`.
     5. If match: rateLimiter.recordSuccess(); build approval URL via `approvalBuilder.build(kPair, request, grantDurationMinutes)`.
     6. Blank `pin` CharArray + `derivedHash` ByteArray in a finally block.
2. `@Composable fun GuardianRequestScreen(uri: Uri, viewModel: ..., onDone: () -> Unit)`:
   - Parse; on error show appropriate message.
   - On success: Card showing target app's icon + display name + package name; "Allow unlock for {appName}?"; PIN entry (numeric, 4-8 digits, masked).
   - Optional slider "Unlock duration: [15 min |||---- 60 min]".
   - Primary button: "Verify & Send Approval" - calls viewModel.verifyPinAndBuildApproval, on success opens Share sheet with the approval URL.
   - Rate-limit message area: "Too many wrong tries. Try again in 0:42."

**Validation.** UI test all three outcome branches.

### T068 - RateLimiter (PIN-guess lockout)

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/guardian/RateLimiter.kt`

**Implementation steps:**

1. `@Singleton class RateLimiter @Inject constructor(@ApplicationContext ctx: Context)`:
   - SharedPreferences-backed (`"krypt_rate_limit"`).
   - State fields: `failureCount: Int`, `lockedUntil: Long` (epoch ms).
   - `fun tryAttempt(): AttemptResult` (Allowed | LockedUntil(ts)).
   - `fun recordFailure()`:
     - `failureCount += 1`.
     - Lockout policy:
       - After 3 failures: 30 seconds.
       - After 5: 2 minutes.
       - After 7: 10 minutes.
       - After 10: 60 minutes, and stays at 60 min for every subsequent failure.
     - `lockedUntil = now + lockoutMillis`.
   - `fun recordSuccess()` - resets to zero.
2. Thread-safe via `synchronized(this)`.

**Validation.** Unit test each threshold.

### T069 - Wire AccessibilityService self-whitelist

**Purpose.** Confirm the FR-011 whitelist string matches the actual fully-qualified class name.

**Files to modify:**
- `app/src/main/java/com/krypt/app/service/AppLockerAccessibilityService.kt` (already has `GUARDIAN_ACTIVITY_CLASS`)

**Implementation steps:**

1. Sanity test: compile-time assertion via `check(GuardianActivity::class.java.name == GUARDIAN_ACTIVITY_CLASS)` in `AppLockerAccessibilityService.onServiceConnected()`. If the string drifts from the class, the service crashes loudly on next connect (better than silently overlaying the Guardian UI).
2. Alternative (cleaner): `object GuardianActivityClass { const val CLASS_NAME = "com.krypt.app.ui.guardian.GuardianActivity" }` shared between Service + Activity; both `require` the class name at startup.

**Validation.** Covered by WP10's `AppLockerAccessibilityServiceTest` which now can exercise the real GuardianActivity launch.

### T070 - Compose UI tests for GuardianRequestScreen

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/ui/guardian/GuardianActivityDispatchTest.kt`
- `app/src/androidTest/java/com/krypt/app/ui/guardian/GuardianRequestScreenUiTest.kt`

**Implementation steps:**

1. `GuardianActivityDispatchTest`: launch with `Intent(ACTION_VIEW, Uri.parse("krypt://request?v=1&req=..."))`; assert `GuardianRequestScreen` text visible.
2. `GuardianRequestScreenUiTest` (with fake ViewModel):
   - Happy path: enter correct PIN -> click "Verify & Send" -> assert Share intent dispatched.
   - Wrong PIN: enter wrong PIN -> assert error message visible; failureCount increments in fake limiter.
   - Rate-limited: enter PIN when limiter is locked -> assert lockout message + countdown.
   - Parse error: invalid URI -> assert error card + Close.

**Validation.** `./gradlew :app:connectedDebugAndroidTest --tests "*guardian*"` green.

## Test Strategy

- **Unit:** RateLimiter thresholds, ViewModel.verifyPinAndBuildApproval (with fakes).
- **Instrumented Compose UI:** T070.
- **Integration:** WP15 adds approval-consumption path; WP17 E2E covers full round-trip.

## Definition of Done

- [ ] GuardianActivity opens on all four `krypt://` authorities and dispatches to the right screen.
- [ ] PIN UI uses NumberPassword keyboard + password-masking; `FLAG_SECURE` applied.
- [ ] Rate-limiter enforces progressive lockout (3/5/7/10 threshold policy).
- [ ] Wrong-PIN outcome does NOT leak timing information (KDF cost is constant for wrong/right).
- [ ] AccessibilityService self-whitelist matches GuardianActivity's actual class name (compile-time or startup-time check).
- [ ] Compose UI tests green.

## Risks + Edge cases

- **PIN-guess timing leak.** KDF runs regardless of PIN correctness; the comparison is `MessageDigest.isEqual` (constant-time). If future optimisation short-circuits (e.g., "PIN first digit wrong -> skip KDF"), that reintroduces a timing side channel. Add a comment on the comparison site: "MUST always run full KDF regardless of PIN shape."
- **Guardian device has no pairing.** User taps a `krypt://request` URL on a device that was installed but never paired. Show a friendly error: "This device is not paired with any Subject. Complete pairing first."
- **Guardian device is a Subject (wrong role).** `guardianRepo.getPairing().role == SUBJECT_OF_GUARDIAN`. Show: "This device is the one being locked, not the Guardian. Open the URL on your Guardian's device instead."
- **PIN zeroing.** Compose `TextField` holds the PIN as `String`, which is immutable and lives in string pool. Defensive: `pin.toCharArray()` at the edge, blank the char array, accept that the `String` may linger (heap analysis threat only; documented limitation).
- **Screen rotation during KDF.** Default Activity recreation would cancel the KDF coroutine. Use `ViewModel.viewModelScope` + `rememberSaveable` on the PIN string. Better: lock `android:screenOrientation="portrait"` on GuardianActivity (many PIN-entry apps do this). Decide based on UX.

## Reviewer Guidance

- Verify `FLAG_SECURE` applied to GuardianActivity window (prevents screenshots of PIN).
- Confirm RateLimiter persists state across process death (SharedPreferences).
- Check the fully-qualified class name matches AppLockerAccessibilityService.GUARDIAN_ACTIVITY_CLASS.
- Paste a demo video of a wrong-PIN + rate-lockout flow in the PR.

## Next command

```
polaris implement WP14 --base WP12
```
