---
work_package_id: WP01
lane: "doing"
dependencies: []
base_branch: main
base_commit: 77f718033dafa4f182a19ca96fb3b5b949b5fddd
created_at: '2026-04-24T05:48:56.035445+00:00'
subtasks: [T001, T002, T003, T004, T005]
test_status: required
test_file: tests/e2e/WP01-wp01-project-scaffold.spec.js
shell_pid: "3028"
---

# WP01 - Project scaffold + Gradle + manifest skeleton

## Objective

Bootstrap the Android project so every downstream WP has a buildable foundation. By the end of this WP, `./gradlew :app:assembleDebug` produces a runnable APK that installs on API 29+ devices and launches a placeholder `MainActivity`. The single most important assertion is that the produced manifest **does not declare `INTERNET`**, enforced by a unit test wired into `./gradlew check`.

## Context

- **Spec:** `polaris-specs/001-krypt-app-locker/spec.md` - FR-005 (no INTERNET), SC-008 (manifest audit).
- **Plan:** `polaris-specs/001-krypt-app-locker/plan.md` - `Technical Context` block for stack choices, `Project Structure` for package layout.
- **Research:** `polaris-specs/001-krypt-app-locker/research.md` - R6 (QUERY_ALL_PACKAGES), R7 (POST_NOTIFICATIONS), R8 (foreground-service types) for the permission block.

## Subtasks

### T001 - Root Gradle + Version Catalog

**Purpose.** Root build script, settings, wrapper, centralised dependency versions.

**Files to create:**
- `settings.gradle.kts`
- `build.gradle.kts` (root, plugin-DSL)
- `gradle.properties`
- `gradle/libs.versions.toml`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`

**Implementation steps:**
1. `settings.gradle.kts`: `pluginManagement` + `dependencyResolutionManagement` blocks pinned to Gradle Plugin Portal + Google Maven + Maven Central. `rootProject.name = "Krypt"`. `include(":app")`.
2. `gradle/libs.versions.toml`: pin AGP 8.6+, Kotlin 2.0.x, Compose BOM 2024.09, Hilt 2.52, Room 2.6.1, kotlinx-coroutines 1.9, DataStore 1.1, security-crypto 1.1.0-alpha06, WorkManager 2.9, Turbine 1.1, MockK 1.13.
3. `gradle.properties`: `android.useAndroidX=true`, `kotlin.code.style=official`, `android.nonTransitiveRClass=true`, `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.jvmargs=-Xmx4g`.
4. Gradle wrapper 8.10+ via `gradle wrapper --gradle-version 8.10`.

**Validation.**
- `./gradlew help` executes cleanly.
- `grep -R "internet" gradle/` returns zero hits.

### T002 - App module build script

**Purpose.** `:app` module Gradle configuration with all plugins + dependencies.

**Files to create:**
- `app/build.gradle.kts`
- `app/proguard-rules.pro` (empty placeholder; populated in WP18)

**Implementation steps:**
1. Plugins: `alias(libs.plugins.android.application)`, `kotlin-android`, `kotlin-kapt` (for Hilt + Room), `alias(libs.plugins.hilt)`.
2. `android { namespace = "com.krypt.app"; compileSdk = 35; defaultConfig { applicationId = "com.krypt.app"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1.0-alpha"; testInstrumentationRunner = "com.krypt.app.KryptTestRunner" } }`.
3. `buildFeatures { compose = true }`; `composeOptions { kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get() }` (or Kotlin 2.0 Compose-plugin equivalent).
4. `kotlinOptions { jvmTarget = "17" }`.
5. Dependencies: Compose BOM + material3 + ui-tooling-preview + activity-compose; Hilt core + hilt-compiler via kapt; Room runtime + ktx + compiler via kapt; DataStore + security-crypto; kotlinx-coroutines-android; AndroidX core-ktx + lifecycle-runtime-ktx + lifecycle-viewmodel-compose; WorkManager-ktx; JUnit4 + Turbine + MockK + androidx.test.ext.junit + compose-ui-test-junit4.

**Validation.**
- `./gradlew :app:assembleDebug` succeeds on empty manifest + stub code.
- `./gradlew :app:dependencies | grep -iE "internet|okhttp|retrofit|ktor|volley"` returns zero hits.

### T003 - AndroidManifest.xml skeleton + permission block

**Purpose.** Declare the complete permission surface for Krypt - and explicitly NOT declare `INTERNET`.

**Files to create:**
- `app/src/main/AndroidManifest.xml`

**Implementation steps:**
1. Root `<manifest xmlns:android="http://schemas.android.com/apk/res/android">` WITHOUT any `package=` (AGP derives from `namespace`).
2. Permission block (order matters for readability, not for OS):
   - `android.permission.SYSTEM_ALERT_WINDOW` (overlay)
   - `android.permission.POST_NOTIFICATIONS`
   - `android.permission.QUERY_ALL_PACKAGES`
   - `android.permission.FOREGROUND_SERVICE`
   - `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
   - `android.permission.FOREGROUND_SERVICE_DATA_SYNC` (for API 29-33 fallback)
   - `android.permission.RECEIVE_BOOT_COMPLETED`
   - `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
3. `<uses-feature android:name="android.software.device_admin" android:required="true" />`.
4. `<application android:name=".KryptApplication" android:allowBackup="false" android:fullBackupContent="false" android:dataExtractionRules="@xml/data_extraction_rules" android:label="@string/app_name" android:theme="@style/Theme.Krypt">`.
5. Inside `<application>`: placeholder `MainActivity` with `<intent-filter>` for `LAUNCHER`. Placeholder entries for `KryptDeviceAdminReceiver` (configured in WP11), `PackageReceiver` (WP08), `AppLockerAccessibilityService` (WP10), `KryptWatchdogService` (WP16), `GuardianActivity` (WP14) may be commented out or marked TODO per WP. For WP01 only `MainActivity` is active.
6. `res/xml/data_extraction_rules.xml` - empty backup rules (allowBackup=false anyway, but API 31+ requires the file to exist).

**CRITICAL.** Do NOT declare `<uses-permission android:name="android.permission.INTERNET" />`. Do NOT declare `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` either (spirit of spec: zero network awareness).

**Validation.**
- `grep -n "INTERNET" app/src/main/AndroidManifest.xml` returns zero hits.
- Manifest validates as XML (`xmllint --noout app/src/main/AndroidManifest.xml` or equivalent).

### T004 - KryptApplication + placeholder MainActivity

**Purpose.** Minimum Kotlin to make the app buildable and installable.

**Files to create:**
- `app/src/main/java/com/krypt/app/KryptApplication.kt`
- `app/src/main/java/com/krypt/app/ui/main/MainActivity.kt`
- `app/src/main/res/values/strings.xml` (just `<string name="app_name">Krypt</string>`)
- `app/src/main/res/values/themes.xml` (skeleton Material3 theme placeholder; full theme in WP12)

**Implementation steps:**
1. `KryptApplication.kt`:
   ```
   package com.krypt.app

   import android.app.Application
   import dagger.hilt.android.HiltAndroidApp

   @HiltAndroidApp
   class KryptApplication : Application() {
       override fun onCreate() {
           super.onCreate()
           // Channels + watchdog wired in WP07 and WP16 respectively.
       }
   }
   ```
2. `MainActivity.kt` - empty `@AndroidEntryPoint class MainActivity : ComponentActivity()` setting `setContent { Text("Krypt") }` placeholder.

**Validation.**
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` succeeds on API 29+ device.
- Launching Krypt shows "Krypt" text.

### T005 - Manifest-audit unit test (FR-005 / SC-008)

**Purpose.** Hard gate against anyone accidentally adding `INTERNET` to the manifest. This is Krypt's defining property.

**Files to create:**
- `app/src/test/java/com/krypt/app/manifest/ManifestAuditTest.kt`

**Implementation steps:**
1. Test reads `app/src/main/AndroidManifest.xml` via `File("src/main/AndroidManifest.xml").readText()` (JVM unit test working directory is the module dir).
2. Parses XML, extracts all `<uses-permission android:name>` values.
3. Asserts NONE equals `"android.permission.INTERNET"`, `"android.permission.ACCESS_NETWORK_STATE"`, `"android.permission.ACCESS_WIFI_STATE"`, `"android.permission.CHANGE_WIFI_STATE"`, `"android.permission.CHANGE_NETWORK_STATE"`.
4. Additionally asserts that expected permissions ARE present: `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `QUERY_ALL_PACKAGES`, `RECEIVE_BOOT_COMPLETED`.
5. Clear failure messages: `"Manifest declares INTERNET - Krypt MUST NOT do this (FR-005). See polaris-specs/001-krypt-app-locker/spec.md section 5."`.

**Validation.**
- `./gradlew :app:testDebugUnitTest --tests "*ManifestAuditTest*"` passes.
- Manually add `<uses-permission android:name="android.permission.INTERNET" />` -> test fails with the prescribed message. Revert.

## Test Strategy

- **Unit (JVM):** `ManifestAuditTest` (T005). Expected <50 ms.
- **Integration:** none at this stage.
- **Manual:** install on a device, verify launch.

Wire `ManifestAuditTest` into `./gradlew check` so that it runs on every CI build AND every subsequent WP's implementation loop.

## Definition of Done

- [ ] `./gradlew :app:assembleDebug` succeeds from a clean checkout.
- [ ] `./gradlew check` succeeds; `ManifestAuditTest` runs and passes.
- [ ] APK installs on API 29 and API 35 devices (or equivalent emulators).
- [ ] Launching the APK shows "Krypt" placeholder UI.
- [ ] No `INTERNET`-adjacent permission appears anywhere in `app/src/main/AndroidManifest.xml` (grep verified).
- [ ] No third-party UI, networking, or crypto libraries in `libs.versions.toml`.
- [ ] All files committed under the branch worktree; no untracked output under `app/build/`.

## Risks + Edge cases

- **Emulator without system images API 35 yet.** CI runners may lag. Fall back to API 34 emulator for CI; keep `targetSdk = 35` in manifest (runtime behaviour is unaffected).
- **Gradle-plugin-Compose-compiler mismatch.** Kotlin 2.0 uses the dedicated Compose Compiler plugin (`org.jetbrains.kotlin.plugin.compose`). Don't mix with the legacy `compose-compiler` extension.
- **`data_extraction_rules.xml` API-level gate.** File is required only for targetSdk 31+. We target 35 so the file must exist and be referenced in `<application android:dataExtractionRules>`.
- **`QUERY_ALL_PACKAGES` + Play Store.** Using this permission blocks non-sideload distribution. Documented as accepted in research.md R6.

## Reviewer Guidance

- Check the manifest permissions list: expected yes, forbidden no. Paste the test output in the review.
- Verify `libs.versions.toml` contains zero networking libraries. Specifically grep for `okhttp`, `retrofit`, `ktor`, `volley`, `moshi-kotlin` (fine), `gson` (fine), `okio` (fine if transitive), `network` (should be zero hits).
- Confirm Gradle wrapper version and build-tools version are documented in the PR description.

## Next command

```
polaris implement WP01
```
