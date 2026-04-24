# Tasks - Krypt (001-krypt-app-locker)

**Feature:** `001-krypt-app-locker`
**Target branch:** `main`
**Planned:** 18 Work Packages, 88 subtasks
**Scale:** ~35 Kotlin files + ~10 XML files + tests. Estimated ~6,500 LOC total.
**MVP scope:** WP01 through WP15 (end-to-end working locker with all seven user scenarios).
**Hardening beyond MVP:** WP16 (battery-survival watchdog) + WP17 (E2E + manual device script) + WP18 (polish + manifest audit).

---

## Dependency graph

```
WP01 (scaffold) --- no deps
|-- WP02 (crypto)
|   |-- WP03 (request/approve links)
|   `-- WP04 (pair/paired links)
|-- WP05 (Room DB entities + DAOs)
|   `-- WP06 (repositories + in-memory + DataStore)
|       |-- WP08 (package receiver)
|       `-- WP10 (accessibility service)
|-- WP07 (notifications)
|   `-- WP08
|-- WP09 (overlay manager)
|   `-- WP10
|-- WP11 (device admin)
`-- WP12 (MainActivity + theme + permission wizard)
    |-- WP13 (pairing UI)
    `-- WP14 (GuardianActivity)
        `-- WP15 (approval consumption + overlay dismiss)
            `-- WP16 (watchdog FGS + heartbeat + OEM hooks)

WP17 (E2E + manual script) --- blocks on WP15
WP18 (polish + audit)      --- blocks on WP17
```

## Parallel opportunities

| After | Can run in parallel |
|-------|---------------------|
| WP01 | WP02 [P], WP05 [P], WP07 [P], WP09 [P], WP11 [P], WP12 [P] |
| WP02 | WP03 [P], WP04 [P] |
| WP06 + WP07 | WP08 (single) |
| WP06 + WP09 | WP10 (single) |
| WP04 + WP06 + WP12 | WP13 [P] |
| WP03 + WP04 + WP06 + WP12 | WP14 [P] |
| WP10 + WP14 | WP15 |
| WP10 | WP16 (parallel with WP15) |

Critical path: `WP01 -> WP02 -> WP03/04 -> ... -> WP14 -> WP15 -> WP17 -> WP18`.

---

## WP01 - Project scaffold + Gradle + manifest skeleton

**Purpose.** Bootstrap the Android project: Gradle KTS + Version Catalog + empty `KryptApplication` + skeleton `AndroidManifest.xml` that explicitly **omits** `INTERNET` and asserts this via a unit test (FR-005).

**Subtasks:** T001-T005 (5 subtasks)

- **T001** - Create root Gradle build (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, wrapper).
- **T002** - Create `app/build.gradle.kts` with Compose + Hilt + Room + Kotlin 2.0 + `minSdk 29 / targetSdk 35 / compileSdk 35`.
- **T003** - Create `app/src/main/AndroidManifest.xml` skeleton with `<application android:name=".KryptApplication">`, placeholder launcher activity, and the full permission block (SYSTEM_ALERT_WINDOW, BIND_ACCESSIBILITY_SERVICE, BIND_DEVICE_ADMIN, POST_NOTIFICATIONS, QUERY_ALL_PACKAGES, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, RECEIVE_BOOT_COMPLETED, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) **without** INTERNET.
- **T004** - `KryptApplication.kt` annotated `@HiltAndroidApp`; launcher `MainActivity` stub.
- **T005** - JVM unit test `ManifestAuditTest` that parses `app/src/main/AndroidManifest.xml` and asserts NO `INTERNET` permission is declared (implements SC-008).

**Dependencies:** none.
**Test status:** required.

---

## WP02 - Crypto primitives (KDF, HKDF, AES-GCM, X25519)

**Purpose.** First-party-only cryptographic primitives: PBKDF2 with adaptive iteration calibration, HKDF-SHA-256, AES-256-GCM, X25519 key-agreement. All under `javax.crypto`; no BouncyCastle add-on.

**Subtasks:** T006-T011 (6 subtasks)

- **T006** - `KdfProvider.kt` - PBKDF2-HMAC-SHA256 with floor 300,000 iterations and `calibrateIterationsForDevice()` targeting >=250 ms cost (FR-008).
- **T007** - `KeyDeriver.kt` - HKDF-SHA-256 wrapper deriving `K_req` from `K_pair + req + salt`.
- **T008** - `AesGcmCipher.kt` - AES-256-GCM encrypt/decrypt with 12-byte random nonce + 16-byte tag.
- **T009** - `X25519KeyAgreement.kt` - ephemeral keypair generation + shared-secret derivation (uses `java.security.KeyPairGenerator` + `KeyAgreement`).
- **T010** - `SecureRandomSource.kt` - thin injectable wrapper around `SecureRandom` for testability.
- **T011** - JVM tests: `KdfProviderTest` (>=250 ms on CI), `KeyDeriverTest` (RFC 5869 test vectors), `AesGcmCipherTest` (round-trip + tamper-detection), `X25519Test` (RFC 7748 test vector).

**Dependencies:** WP01.
**Test status:** required.

---

## WP03 - Deep-link scheme: `krypt://request` + `krypt://approve`

**Purpose.** Implement the two hot-path deep-link URIs (spec FR-006, FR-007, FR-009, FR-015). Uses WP02 crypto for approval encryption.

**Subtasks:** T012-T016 (5 subtasks)

- **T012** - `DeepLinkScheme.kt` - constants for scheme (`krypt`), authorities (`pair`, `paired`, `request`, `approve`), and `Base64.URL_SAFE | NO_WRAP | NO_PADDING` helpers.
- **T013** - `UnlockRequestBuilder.kt` + `UnlockRequestParser.kt` - per `contracts/request.md`.
- **T014** - `ApprovalLinkBuilder.kt` + `ApprovalLinkParser.kt` + `ApprovalPayloadCodec.kt` (CBOR encode/decode) - per `contracts/approve.md`.
- **T015** - `ApprovalConsumer.kt` - orchestrates parse -> lookup OutstandingRequest -> derive K_req -> decrypt -> consistency check -> return `ApprovalOutcome`. Pure business logic; no Android dependencies.
- **T016** - JVM round-trip tests: `UnlockRequestRoundTripTest`, `ApprovalRoundTripTest`, `ApprovalConsumerTest` (exhaustive error-case coverage from `contracts/approve.md`).

**Dependencies:** WP01, WP02.
**Test status:** required.

---

## WP04 - Deep-link scheme: `krypt://pair` + `krypt://paired`

**Purpose.** Pairing-time URIs: Subject emits `krypt://pair` on onboarding; Guardian replies with HMAC-authenticated `krypt://paired`. Establishes `K_pair` shared secret.

**Subtasks:** T017-T021 (5 subtasks)

- **T017** - `PairRequestBuilder.kt` + `PairRequestParser.kt` - per `contracts/pair.md`.
- **T018** - `PairedReplyBuilder.kt` (Guardian side) - performs X25519 agreement, HMAC-SHA-256 over canonical input, emits URL.
- **T019** - `PairedReplyVerifier.kt` (Subject side) - parses URL, derives `K_pair`, verifies HMAC, returns `PairedReply` + `K_pair` tuple.
- **T020** - `KPairStore.kt` - interface for persisted `K_pair` (implementation backed by EncryptedSharedPreferences is in WP06).
- **T021** - JVM tests: `PairRoundTripTest`, `PairedReplyMacTamperTest` (negative: flipped bits in any MAC-covered field -> BadMac).

**Dependencies:** WP01, WP02.
**Test status:** required.

---

## WP05 - Data layer: Room database + entities + DAOs

**Purpose.** Room schema per `data-model.md`: `LockedApp`, `GuardianPairing`, `OutstandingRequest`, `UnlockGrant`. Migrations strategy declared (future-proof; v1 is version 1).

**Subtasks:** T022-T026 (5 subtasks)

- **T022** - `LockedAppEntity.kt` + `LockedAppDao.kt` + `LockState.kt` + `LockSource.kt` enums.
- **T023** - `GuardianPairingEntity.kt` + `GuardianPairingDao.kt` + `PairingRole.kt` enum.
- **T024** - `OutstandingRequestEntity.kt` + `OutstandingRequestDao.kt` + transaction method `markConsumedIfOpen(requestId): Boolean` (returns true if row flipped 0->1).
- **T025** - `UnlockGrantEntity.kt` + `UnlockGrantDao.kt` (query `activeGrantsForPackage(pkg, now): Flow<List<UnlockGrantEntity>>`).
- **T026** - `KryptDatabase.kt` Room DB class, `@TypeConverters` for ByteArray/Instant, androidTest `KryptDatabaseTest` (in-memory instance, per-DAO smoke tests + `markConsumedIfOpen` race test).

**Dependencies:** WP01.
**Test status:** required.

---

## WP06 - Data layer: repositories, in-memory stores, DataStore settings

**Purpose.** Repository abstractions on top of DAOs (per plan "mock seam" requirement FR-016), plus in-memory fast-path cache and DataStore-backed settings.

**Subtasks:** T027-T031 (5 subtasks)

- **T027** - `LockedAppsRepository.kt` interface + `RoomLockedAppsRepository.kt` impl + `FakeLockedAppsRepository.kt` (in-memory, used by service tests).
- **T028** - `GuardianRepository.kt` with `observePairing(): Flow<GuardianPairing?>` + in-memory cache. `KPairStore.kt` implementation backed by EncryptedSharedPreferences.
- **T029** - `OutstandingRequestRepository.kt` + `UnlockGrantRepository.kt`.
- **T030** - `LockerSessionStore.kt` (in-memory `ConcurrentHashMap<String,Long>`), rebuilt at Application start from `UnlockGrantDao.activeAt(now)`. `recordGrant`, `isUnlockedNow`, `pruneExpired`.
- **T031** - `SettingsRepository.kt` (Proto DataStore) with `kdfIterations`, `defaultGrantMinutes`, `onboardingComplete`, `lastGuardianPairAt`. JVM + androidTest coverage.

**Dependencies:** WP05.
**Test status:** required.

---

## WP07 - Notifications (channel + helper + runtime permission)

**Purpose.** Security-Alerts channel registration + `NotificationHelper.notifyAppLocked(pkg, displayName)` (FR-004). Runtime `POST_NOTIFICATIONS` gate.

**Subtasks:** T032-T035 (4 subtasks)

- **T032** - `SecurityAlertsChannel.kt` - channel ID `"krypt.security_alerts"`, `IMPORTANCE_HIGH`, created at Application start.
- **T033** - `NotificationHelper.kt` - builds + posts "New App Protected: {pkg} has been locked by default." with tap-intent to MainActivity locked-apps screen.
- **T034** - `NotificationPermissionHelper.kt` - API-33+ runtime permission request with rationale (used during onboarding; WP12).
- **T035** - androidTest `NotificationHelperTest` - posts a notification, queries `NotificationManager.activeNotifications`, asserts channel + text.

**Dependencies:** WP01.
**Test status:** required.

---

## WP08 - Package receiver (Default-Deny Engine)

**Purpose.** Listen for `ACTION_PACKAGE_ADDED`, insert into `LockedAppsRepository`, fire a notification. Implements FR-003, FR-004.

**Subtasks:** T036-T039 (4 subtasks)

- **T036** - `PackageReceiver.kt` annotated `@AndroidEntryPoint`; injects `LockedAppsRepository` + `NotificationHelper`. Registered in manifest for `android.intent.action.PACKAGE_ADDED` + `data="package"`.
- **T037** - Extract package name from intent URI (`intent.data.schemeSpecificPart`), suppress `EXTRA_REPLACING` true (upgrades, not new installs).
- **T038** - Resolve display name via `PackageManager.getApplicationLabel()`; wrap DB + notification work in `goAsync()` + `CoroutineScope(SupervisorJob())` since BroadcastReceiver lifecycle is short.
- **T039** - androidTest `PackageReceiverTest` - programmatically install a test APK (small asset) and verify `LockedAppsRepository.findByPackage` returns a row + notification appears.

**Dependencies:** WP06, WP07.
**Test status:** required.

---

## WP09 - OverlayManager + XML locker overlay layout

**Purpose.** `WindowManager`-backed fullscreen non-dismissible overlay. Pre-inflated at service start. Implements FR-002, contributes to FR-001.

**Subtasks:** T040-T044 (5 subtasks)

- **T040** - `res/layout/locker_overlay.xml` - `FrameLayout` with app icon, app name, cooldown text, "Ask Guardian" button, "Pending" spinner. Material-themed.
- **T041** - `OverlayManager.kt` - singleton scoped to Application. `preinflate(context)` at service start. `show(pkg, displayName)` assembles `WindowManager.LayoutParams` per research.md R2 (TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS | SECURE | KEEP_SCREEN_ON | HARDWARE_ACCELERATED | SHOW_WHEN_LOCKED).
- **T042** - Insets handling: API 30+ `WindowInsetsController.hide(systemBars())`; API 29 fallback `View.setSystemUiVisibility(IMMERSIVE_STICKY | ...)`. Back-key dispatch consumed inside overlay root view.
- **T043** - `hide()` (safe if not shown), `update(pkg)` (swap content without remove/add), `isShowing`. All methods main-thread; internal `Handler(Looper.getMainLooper())` posting from service callers.
- **T044** - androidTest `OverlayManagerTest` (requires `SYSTEM_ALERT_WINDOW` granted via adb) - show / update / hide; verify `WindowManager` view count changes.

**Dependencies:** WP01.
**Test status:** required.

---

## WP10 - AppLockerAccessibilityService (Interception Layer)

**Purpose.** Core FR-001 interception. Observes `TYPE_WINDOW_STATE_CHANGED`, decides show/hide overlay based on `LockedAppsRepository` + `LockerSessionStore`. Self-whitelists `GuardianActivity` (FR-011).

**Subtasks:** T045-T050 (6 subtasks)

- **T045** - `res/xml/accessibility_service_config.xml` per research.md R1: `accessibilityEventTypes=typeWindowStateChanged`, `notificationTimeout=100`, `canRetrieveWindowContent=false`.
- **T046** - `AppLockerAccessibilityService.kt` annotated `@AndroidEntryPoint`; declared in manifest with `android.permission.BIND_ACCESSIBILITY_SERVICE` and `<meta-data>` pointing to the XML config.
- **T047** - `onServiceConnected()` - pre-inflate overlay via `OverlayManager.preinflate`, rebuild `LockerSessionStore` from DB, subscribe to `LockedAppsRepository.observeLockedApps()` as a `StateFlow`.
- **T048** - `onAccessibilityEvent(ev)` hot path: extract pkg; fast-path `if (pkg in unlockedPackagesCache) return`; check own whitelist (GuardianActivity fully-qualified name); fall through to DB check (synchronous on cached Flow value); call `OverlayManager.show(pkg)` or `hide()`.
- **T049** - Latency logging: timestamp event receipt, timestamp overlay `addView` return, log p95 to Logcat under `KryptA11y` tag for SC-001 verification.
- **T050** - androidTest `AppLockerAccessibilityServiceTest` - uses `UiAutomation` to grant self-accessibility; launches a known package; asserts overlay shows; asserts GuardianActivity launch does NOT show overlay.

**Dependencies:** WP06, WP09.
**Test status:** required.

---

## WP11 - Device Admin (uninstall friction)

**Purpose.** Implements FR-012. `DeviceAdminReceiver` that the user activates during onboarding; Android OS then gates uninstall.

**Subtasks:** T051-T054 (4 subtasks)

- **T051** - `res/xml/device_admin.xml` - policy list: `USES_POLICY_FORCE_LOCK` (minimal; we don't actually force-lock, it's the marker that keeps us as an active admin). Other policies omitted to minimise attack surface.
- **T052** - `KryptDeviceAdminReceiver.kt` - overrides `onEnabled`, `onDisableRequested` (returns deterrent warning string), `onDisabled`. Manifest: permission `BIND_DEVICE_ADMIN`, action `android.app.action.DEVICE_ADMIN_ENABLED`.
- **T053** - `DeviceAdminHelper.kt` - `requestActivation(activity)` starts `ACTION_ADD_DEVICE_ADMIN` intent with EXTRA_DEVICE_ADMIN + EXTRA_ADD_EXPLANATION. Used by WP12 onboarding.
- **T054** - Manual test script entry `docs/manual-device-admin.md`: activate -> verify uninstall blocked -> revoke -> verify uninstall allowed. (Automated uninstall-block test is not possible without test-harness root; documented in risks.)

**Dependencies:** WP01.
**Test status:** required (manual script).

---

## WP12 - UI scaffold: MainActivity + Compose theme + permission wizard

**Purpose.** Main entry Activity. Compose + Material3 theme. Onboarding flow that walks the Administrator through Accessibility / Overlay / Device-Admin / POST_NOTIFICATIONS / battery-optimisation privileges.

**Subtasks:** T055-T059 (5 subtasks)

- **T055** - `ui/theme/KryptTheme.kt` (Material3 `ColorScheme` light + dark), `ui/theme/Type.kt`, `ui/theme/Shapes.kt`.
- **T056** - `ui/main/MainActivity.kt` annotated `@AndroidEntryPoint` + Compose host; reads `SettingsRepository.onboardingComplete` to decide between OnboardingScreen or HomeScreen.
- **T057** - `ui/main/OnboardingScreen.kt` - 5-step Compose wizard (one `rememberSaveable` index): Accessibility, Overlay, Device-Admin, POST_NOTIFICATIONS, Battery. Each step checks its grant state and shows "Grant" button that launches the appropriate Intent.
- **T058** - `ui/main/HomeScreen.kt` (placeholder for v1): shows pairing status + "Re-share pairing link" + "Locked apps (N)" summary. Full locked-apps management UI deferred to post-v1.
- **T059** - Compose UI tests `OnboardingScreenUiTest`, `MainActivityRoutingTest` (mock `SettingsRepository` returns onboardingComplete false/true -> asserts which screen is shown).

**Dependencies:** WP01.
**Test status:** required.

---

## WP13 - UI: Guardian pairing screens (pair emit + paired consume)

**Purpose.** First-time Guardian pairing: generate `krypt://pair` on Subject; handle `krypt://paired` on Subject; Guardian-side PIN-set + `krypt://paired` emit.

**Subtasks:** T060-T064 (5 subtasks)

- **T060** - `ui/pairing/PairingRoleChooserScreen.kt` - "This device holds the PIN" (Guardian) vs "This device runs the locker" (Subject). Writes role to `GuardianPairing.role`.
- **T061** - `ui/pairing/SubjectPairScreen.kt` - generates Subject ephemeral keypair (held in `PairingSessionViewModel`), builds `krypt://pair` URL, shows copy-to-clipboard + Share-sheet + QR (text-only QR for v1 via built-in QR rendering or plain-text fallback).
- **T062** - `ui/pairing/GuardianPairConsumeScreen.kt` - entered when Guardian device opens `krypt://pair` deep-link. Prompts set-PIN (first time), shows "Confirm pairing with 'Alice's Pixel'? [Yes] [No]", on Yes calls `PairedReplyBuilder` and opens Share sheet with `krypt://paired` URL.
- **T063** - `ui/pairing/SubjectPairedConsumeScreen.kt` - entered when Subject device opens `krypt://paired` deep-link. Verifies HMAC, persists `K_pair` via `KPairStore`, writes `GuardianPairing` row, marks `onboardingComplete=true` (if role already chosen).
- **T064** - Compose UI tests for all four screens (mocks for `PairRequestBuilder` / `PairedReplyBuilder` / repositories).

**Dependencies:** WP04, WP06, WP12.
**Test status:** required.

---

## WP14 - UI: GuardianActivity (request-consume + PIN UI + approval-emit)

**Purpose.** Single Activity hosting both `krypt://request` and `krypt://approve` deep-link entry points on the Guardian side. This Activity is the ONE whitelisted from self-interception (FR-011).

**Subtasks:** T065-T070 (6 subtasks)

- **T065** - `ui/guardian/GuardianActivity.kt` annotated `@AndroidEntryPoint` + Compose host; manifest `<intent-filter>` for BOTH `krypt://request` and `krypt://approve` (`<data android:scheme="krypt">`).
- **T066** - `GuardianActivity.onCreate` - parses `intent.data` URI; dispatches to `GuardianRequestScreen` OR `GuardianApprovalConsumeScreen` based on authority.
- **T067** - `ui/guardian/GuardianRequestScreen.kt` - shows target package icon + name, PIN entry field (numeric IME), "Send Approval" button. On tap: runs `KdfProvider.verifyPin` off the main thread; on valid PIN calls `ApprovalLinkBuilder.build` and opens Share sheet.
- **T068** - `ui/guardian/RateLimiter.kt` - incorrect-PIN lockout: exponential backoff after 3 failures, max 1 attempt per 60 s after 10 failures. Persisted in SharedPreferences.
- **T069** - Whitelist hook: `AppLockerAccessibilityService` checks for `com.krypt.app.ui.guardian.GuardianActivity` class name and skips the overlay.
- **T070** - Compose UI tests: `GuardianRequestScreenUiTest` (valid PIN path, invalid PIN rate-limited path, corrupted URL path).

**Dependencies:** WP03, WP04, WP06, WP12.
**Test status:** required.

---

## WP15 - Approval consumption + overlay dismiss integration

**Purpose.** End-to-end wire-up: Subject device receives `krypt://approve` -> decrypts -> inserts `UnlockGrant` -> updates `LockerSessionStore` -> `AppLockerAccessibilityService` hides overlay. Covers FR-013, FR-015.

**Subtasks:** T071-T075 (5 subtasks)

- **T071** - `ui/guardian/GuardianApprovalConsumeScreen.kt` - Compose screen entered on `krypt://approve` deep-link on the Subject device. Runs `ApprovalConsumer.consumeApproval` (WP03 T015); displays success (green check + "Unlocked com.example.app until 10:57 (15 min)") or per-error message from `contracts/approve.md`.
- **T072** - On success: `LockerSessionStore.recordGrant(pkg, expiresAt)` (in-memory fast-path) + `UnlockGrantDao.insert` (persistence). Both operations wrapped in a single transaction via `@Transaction` DAO method.
- **T073** - `OverlayManager.hide()` called from the Subject's `GuardianApprovalConsumeScreen` if the currently-shown overlay's package matches the approved package. Safe no-op if overlay not shown.
- **T074** - Auto-finish `GuardianActivity` after 3 seconds on success, immediately relaunch the target app via `PackageManager.getLaunchIntentForPackage(pkg)`.
- **T075** - androidTest `ApprovalConsumptionIntegrationTest` - full-stack: seed an OutstandingRequest, deliver a valid krypt://approve intent, assert UnlockGrant row inserted + LockerSessionStore state + overlay dismissed.

**Dependencies:** WP10, WP14.
**Test status:** required.

---

## WP16 - Watchdog FGS + WorkManager heartbeat + OEM battery hooks

**Purpose.** Defence against OEM aggressive killers + Doze. Implements FR-010 and contributes to SC-004.

**Subtasks:** T076-T080 (5 subtasks)

- **T076** - `service/KryptWatchdogService.kt` - minimal foreground service, `foregroundServiceType="specialUse|dataSync"`, posts a low-importance "Krypt is protecting your device" notification. Started from Application.onCreate on API 29+.
- **T077** - Manifest `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="accessibilityServiceSupervisor">`.
- **T078** - `worker/AccessibilityHealthWorker.kt` - periodic `CoroutineWorker` (15 min) checking `AccessibilityManager.getEnabledAccessibilityServiceList()`. If Krypt absent, posts high-priority notification "Krypt Accessibility is OFF - tap to re-enable".
- **T079** - `OemBatteryLinks.kt` - lookup table of OEM-specific Settings deep-links (`Build.MANUFACTURER` -> intent ComponentName) for Xiaomi, Huawei, Oppo, Vivo, Samsung. Wired into WP12 OnboardingScreen step 5 ("Protect Krypt from battery killer").
- **T080** - androidTest `AccessibilityHealthWorkerTest` using `WorkManagerTestInitHelper`; verifies notification emission when accessibility-disabled condition is mocked.

**Dependencies:** WP10.
**Test status:** required.

---

## WP17 - E2E tests + manual device script + docs

**Purpose.** Feature-scoped regression suite under `tests/krypt-app-locker/`. Executable E2E on-device test + a manual device-test script for the inherently-manual bits (AccessibilityService latency measurement, overlay z-order, real uninstall attempts).

**Subtasks:** T081-T084 (4 subtasks)

- **T081** - `tests/krypt-app-locker/e2e/EndToEndUnlockRoundTripTest.kt` (androidTest) - exercises full flow: seed pairing, install a test package, assert lock, simulate request-URL generation, simulate approval-URL delivery, assert unlock. Single mega-test to prove the pipeline.
- **T082** - `tests/krypt-app-locker/docs/manual-test-script.md` - numbered checklist covering US-1 through US-7, latency measurement via Logcat grep, OEM-specific battery-survival test procedure.
- **T083** - `tests/krypt-app-locker/docs/security-audit-checklist.md` - items to verify: `aapt dump permissions` shows no INTERNET; 72-hour network-egress soak procedure; KDF benchmark script.
- **T084** - CI workflow file `.github/workflows/android-ci.yml` - runs JVM unit tests + lint; androidTest left as manual (no emulator-compatible runner for AccessibilityService at present).

**Dependencies:** WP15.
**Test status:** required.

---

## WP18 - Polish: ProGuard/R8 + string resources + a11y labels + manifest audit

**Purpose.** Release hardening. Strings externalised to `res/values/strings.xml`. Compose accessibility labels. ProGuard rules for Hilt/Room/Compose. Final manifest-audit CI gate.

**Subtasks:** T085-T088 (4 subtasks)

- **T085** - Externalise all hardcoded strings in Kotlin + Compose to `res/values/strings.xml`; add `res/values-v19/strings.xml` for RTL fallback note (none required but shim in place).
- **T086** - `app/proguard-rules.pro` - keep rules for Hilt (`@dagger.*`), Room (`* extends RoomDatabase`), Compose (`@Composable`), Kotlin serialization if used.
- **T087** - Compose `contentDescription` audit: every `Icon` + `Image` has a non-null content description OR `null` with explicit reason comment.
- **T088** - Gradle task `verifyManifest` (written in `app/build.gradle.kts`) that `aapt dump permissions` the assembled APK and fails if `android.permission.INTERNET` appears. Wired into `check` lifecycle and CI.

**Dependencies:** WP17.
**Test status:** required.

---

## MVP scope summary

| Phase | WPs | Delivers |
|-------|-----|----------|
| **Setup** | WP01 | Buildable empty app with correct manifest + permissions |
| **Foundations** | WP02, WP05, WP07, WP09, WP11, WP12 | Crypto, DB, notifications, overlay, device-admin, main Activity scaffold (parallel-safe) |
| **Protocol** | WP03, WP04, WP06 | Deep-link scheme + repositories (spec's peer-to-peer crypto flow) |
| **Core features** | WP08, WP10 | Default-Deny engine + Interception layer (spec pillars 1 + 2) |
| **Pairing + Guardian** | WP13, WP14 | Subject-Guardian pairing UI + Guardian approval flow (spec pillars 3) |
| **End-to-end unlock** | WP15 | Completes the circle: approval -> grant -> overlay dismiss |
| **Resilience** | WP16 | Battery-optimisation survival (spec FR-010) |
| **Verification** | WP17, WP18 | Tests + docs + release polish |

**Minimum viable delivery:** WP01 - WP15. Ships all seven user scenarios.
**Ship-quality delivery:** + WP16 (battery survival) + WP18 (manifest audit) + WP17 at least to the manual-script level.

---

## Commit workflow

Each WP is implemented via `polaris implement WPxx` in its own worktree. Dependencies in frontmatter are enforced (e.g. `polaris implement WP02 --base WP01` picks up WP01's changes). Merge order follows the dependency graph.
