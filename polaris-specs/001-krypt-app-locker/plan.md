# Implementation Plan: Krypt - Zero-Trust Offline App Locker

**Branch**: `main` (feature: `001-krypt-app-locker`) | **Date**: 2026-04-24 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `polaris-specs/001-krypt-app-locker/spec.md`

## Summary

Krypt intercepts every launch of a locked app on a Subject device with a fullscreen non-dismissible overlay, and delegates unlock authority to a Guardian holding a PIN on a separate device. Request and approval flow across any offline channel as signed + encrypted deep-link URLs. Foundation is a single Gradle `:app` module in Kotlin 2.0+ using Jetpack Compose + Material3 (Activities), XML views inside the `WindowManager` overlay, Hilt for DI, Room for persistence, and standard AndroidX crypto (PBKDF2-HMAC-SHA256 + AES-256-GCM). The manifest ships without the `INTERNET` permission and enforces Device-Admin to friction uninstall.

## Technical Context

**Language/Version**: Kotlin 2.0+ (JVM target 17), Android SDK 35
**Primary Dependencies**:
  - `androidx.compose.bom` + `androidx.compose.material3` (Activities UI)
  - `androidx.hilt` + `hilt-compiler` (DI)
  - `androidx.room` + `room-ktx` (persistence)
  - `androidx.security:security-crypto` (PBKDF2 helpers, EncryptedSharedPreferences for at-rest PIN salt storage on Guardian device)
  - `androidx.core:core-ktx`, `androidx.lifecycle:lifecycle-*`
  - `androidx.datastore:datastore-preferences` (settings)
  - No third-party UI or networking libraries
**Storage**: Room (SQLite) for LockedApp / GuardianPairing / OutstandingRequest / UnlockGrant; DataStore for scalar settings; EncryptedSharedPreferences on the Guardian device for pub-salt at rest.
**Testing**: JUnit4 + Turbine (Flow) + MockK + AndroidX Test + Compose UI Test. Manual device-test script for AccessibilityService latency + overlay z-order.
**Target Platform**: Android 10 (API 29) through Android 15 (API 35) on phone form factors.
**Project Type**: Mobile (Android single-module `:app`).
**Performance Goals**: 95p foreground-to-overlay latency <=200 ms; PIN-KDF cost >=250 ms; zero network egress.
**Constraints**: No `INTERNET` permission in manifest; overlay must persist across Doze / App-Standby; Device-Admin uninstall friction; offline-only deep-link transport.
**Scale/Scope**: One app, two install sides (Subject / Guardian - same APK, different runtime mode). Estimated ~35 Kotlin files + ~10 XML files at foundation.

## Constitution Check

No `.polaris/memory/constitution.md` exists in this repository. Skipping constitution gates. If a constitution is adopted later, the gates that would be expected for a security-sensitive mobile feature are:

- Privacy: zero-telemetry by manifest (satisfied: `INTERNET` omitted).
- Reversibility: user can revoke Device-Admin and uninstall (satisfied: Device-Admin, not Device-Owner).
- Scope discipline: foundation ships only FR-001..FR-016 and defers biometric / QR / tamper-detect.

## Project Structure

### Documentation (this feature)

```
polaris-specs/001-krypt-app-locker/
|-- plan.md              # This file
|-- spec.md              # Feature spec (already committed)
|-- control-map.md       # Runtime-flow map (already committed)
|-- research.md          # Phase 0 output
|-- data-model.md        # Phase 1 output
|-- quickstart.md        # Phase 1 output
|-- contracts/           # Phase 1 output (deep-link URI contracts)
|   |-- pair.md
|   |-- paired.md
|   |-- request.md
|   |-- approve.md
|-- checklists/
|   `-- requirements.md  # Spec-quality checklist (already committed)
|-- meta.json            # Feature metadata (already committed)
`-- tasks/               # (Populated by /polaris.tasks)
```

### Source Code (repository root)

Single Android `:app` module. Proposed package layout under `app/src/main/java/com/krypt/app`:

```
app/
|-- build.gradle.kts
`-- src/
    |-- main/
    |   |-- AndroidManifest.xml
    |   |-- java/com/krypt/app/
    |   |   |-- KryptApplication.kt              # @HiltAndroidApp
    |   |   |-- di/
    |   |   |   `-- AppModule.kt
    |   |   |-- ui/
    |   |   |   |-- main/MainActivity.kt         # Compose: onboarding host
    |   |   |   |-- main/OnboardingScreen.kt     # Compose
    |   |   |   |-- pairing/GuardianPairingScreen.kt
    |   |   |   |-- guardian/GuardianActivity.kt # Deep-link entry
    |   |   |   |-- guardian/GuardianPinScreen.kt
    |   |   |   |-- overlay/LockerOverlayView.kt # XML-backed (NOT Compose)
    |   |   |   |-- theme/                       # Material3 theme
    |   |   |-- service/
    |   |   |   |-- AppLockerAccessibilityService.kt
    |   |   |   |-- DeviceAdminReceiver.kt
    |   |   |   `-- OverlayManager.kt            # Wraps WindowManager
    |   |   |-- receiver/
    |   |   |   `-- PackageReceiver.kt           # ACTION_PACKAGE_ADDED
    |   |   |-- data/
    |   |   |   |-- LockedAppEntity.kt
    |   |   |   |-- LockedAppDao.kt
    |   |   |   |-- GuardianPairingEntity.kt
    |   |   |   |-- OutstandingRequestEntity.kt
    |   |   |   |-- UnlockGrantEntity.kt
    |   |   |   |-- KryptDatabase.kt             # Room DB
    |   |   |   |-- LockedAppsRepository.kt      # Mock seam for spec FR-016
    |   |   |   |-- GuardianRepository.kt
    |   |   |   `-- LockerSessionStore.kt        # In-memory grants
    |   |   |-- crypto/
    |   |   |   |-- KdfProvider.kt               # PBKDF2-HMAC-SHA256 >=300k iter
    |   |   |   |-- ProofVerifier.kt
    |   |   |   |-- KeyDeriver.kt                # HKDF PIN-material -> AES-256
    |   |   |   `-- AesGcmCipher.kt
    |   |   |-- deeplink/
    |   |   |   |-- DeepLinkScheme.kt            # constants
    |   |   |   |-- UnlockRequestBuilder.kt
    |   |   |   |-- UnlockRequestParser.kt
    |   |   |   |-- ApprovalLinkBuilder.kt
    |   |   |   `-- ApprovalLinkParser.kt
    |   |   `-- notifications/
    |   |       |-- SecurityAlertsChannel.kt
    |   |       `-- NotificationHelper.kt
    |   `-- res/
    |       |-- layout/locker_overlay.xml
    |       |-- xml/accessibility_service_config.xml
    |       |-- xml/device_admin.xml
    |       |-- values/strings.xml
    |       `-- values/themes.xml
    |-- test/              # JVM unit tests (JUnit4 + Turbine + MockK)
    |   `-- java/com/krypt/app/
    |       |-- crypto/KdfProviderTest.kt
    |       |-- crypto/ProofVerifierTest.kt
    |       |-- crypto/AesGcmCipherTest.kt
    |       |-- deeplink/UnlockRequestRoundTripTest.kt
    |       |-- deeplink/ApprovalRoundTripTest.kt
    |       `-- data/LockedAppsRepositoryTest.kt
    `-- androidTest/       # Device tests
        `-- java/com/krypt/app/
            |-- data/KryptDatabaseTest.kt        # Room in-memory
            |-- ui/guardian/GuardianActivityUiTest.kt # Compose UI Test
            `-- ui/main/OnboardingScreenUiTest.kt
```

**Structure Decision**: Single Gradle `:app` module. Packages organised by horizontal concern (`data`, `crypto`, `deeplink`, `service`, `receiver`, `notifications`, `ui/*`) not by vertical feature, because cross-cutting components (CryptoHelper, OverlayManager, LockedAppsRepository) are used by multiple flows (see `control-map.md`). Multi-module extraction deferred until codebase exceeds the "single-module pain point" (typically ~150+ files or build times > 60s).

## Phase 0 - Research

Consolidated in `research.md`. Topics investigated:

1. AccessibilityService event filter tuning for <200 ms overlay latency
2. WindowManager overlay flags for genuinely non-dismissible UI (TYPE_APPLICATION_OVERLAY with FLAG_NOT_FOCUSABLE + FLAG_LAYOUT_IN_SCREEN + FLAG_FULLSCREEN + secure-flag tradeoffs)
3. Battery-optimisation survival: foreground service type, ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, periodic WorkManager heartbeat as watchdog
4. PBKDF2 vs scrypt vs Argon2 on AndroidX - chosen: PBKDF2-HMAC-SHA256 with >=300k iterations (first-party, no BouncyCastle add-on)
5. Deep-link URI size budget vs messenger payload limits (WhatsApp hard cap ~65k chars; SMS splits at 160; design target under 512 chars)
6. API 30+ package-visibility rules (`<queries>` manifest element vs `QUERY_ALL_PACKAGES`)
7. API 33+ runtime `POST_NOTIFICATIONS` permission flow
8. API 34+ foreground-service type declarations
9. Hilt injection into `AccessibilityService` and `BroadcastReceiver` (requires `@AndroidEntryPoint` on each)
10. Compose-in-WindowManager-overlay lifecycle pitfalls (why we use XML for the overlay specifically)

## Phase 1 - Design

Artifacts produced:

- **`data-model.md`** - Room entities, DAOs, in-memory stores, entity relationships, lifecycle semantics.
- **`contracts/`** - Four deep-link contract files (`pair.md`, `paired.md`, `request.md`, `approve.md`), each specifying URL format, parameter encoding, cryptographic invariants, builder/parser signatures, and error cases.
- **`quickstart.md`** - Developer getting-started: environment, build, install-on-two-devices, manual verification of the seven user scenarios.

## Phase 2 - Task Planning Approach

Left to `/polaris.tasks`. Expected shape based on control-map: 1 setup WP, 4-5 foundational WPs (data, crypto, deeplink, overlay, notifications), 1-2 UI WPs (onboarding, guardian), 1 integration WP (end-to-end request/approve), 1 polish WP. Rough estimate: 8-12 Work Packages.

## Complexity Tracking

No constitution gates to violate. No complexity justifications required.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| *(none)*  |            |                                      |

## Engineering Alignment (captured from planning interrogation)

| Decision | Outcome |
|----------|---------|
| Module structure | Single `:app` module |
| Dependency injection | Hilt |
| Pairing UX (resolves spec clarification #1) | One-time `krypt://pair` -> `krypt://paired` round-trip, TOFU trust on first reply; Guardian's public salt stored locally on Subject |
| PIN rotation (resolves spec clarification #2) | Rotating PIN regenerates the public salt and re-issues `krypt://paired` to Subject; outstanding `krypt://request` URLs become uncomputable |
| Testing | JUnit4 + Turbine + MockK for unit; Compose UI Test for Activities; manual device-test script for AccessibilityService + overlay |
| UI framework | Jetpack Compose + Material3 (Activities); XML view for WindowManager overlay |
| Storage | Room (SQLite) + DataStore + EncryptedSharedPreferences |
| Crypto | PBKDF2-HMAC-SHA256 (>=300,000 iterations) + HKDF + AES-256-GCM, all via `javax.crypto` / AndroidX first-party |
| Target API | minSdk 29, targetSdk 35, compileSdk 35 |
| Network | Zero. `INTERNET` permission intentionally absent. |
| Uninstall protection | Regular Device-Admin (user-revocable) |
