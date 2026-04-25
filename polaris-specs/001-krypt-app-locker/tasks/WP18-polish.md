---
work_package_id: WP18
lane: done
dependencies: []
subtasks: [T085, T086, T087, T088]
agent: claude
test_status: required
test_file: tests/e2e/WP18-wp18-polish.spec.js
review_status: approved
reviewed_by: Prasath Kumar K
domain: frontend-craft
---

# WP18 - Polish: ProGuard + strings + a11y labels + manifest-audit Gradle task

## Objective

Release hardening. Externalise all hardcoded strings to `res/values/strings.xml`. Audit content descriptions on every `Icon`/`Image` for accessibility. Add ProGuard/R8 keep rules for Hilt/Room/Compose/Kotlin reflection boundaries. Introduce a Gradle task that `aapt dump`s the assembled APK and fails the build if `INTERNET` appears - wired into the `check` lifecycle and CI.

## Context

- **Spec SC-008:** "The shipped APK's manifest, inspected by aapt dump permissions, contains zero occurrences of INTERNET."
- **Plan "Out of scope -> plan polish / hardening."** This is the release-hardening bucket.

## Subtasks

### T085 - Externalise all hardcoded strings

**Files to modify:**
- `app/src/main/res/values/strings.xml` (add new entries)
- Various Kotlin and Compose files (replace literals)

**Implementation steps:**

1. Grep for hardcoded English text in `app/src/main/java/**/*.kt`:
   ```
   grep -RnE '"[A-Z][^"]{5,}"' app/src/main/java | grep -v "import\|Log\." | less
   ```
2. Move every user-visible string into `strings.xml` with a semantic key. Examples: `welcome_title`, `onboarding_step1_body`, `guardian_wrong_pin`, etc.
3. In Kotlin: replace with `stringResource(R.string.welcome_title)` inside Composables, or `context.getString(R.string.welcome_title)` elsewhere.
4. Use placeholders `%1$s` for interpolation (e.g., "New App Protected: %1$s"). Pass values to `stringResource(id, arg1, arg2)`.
5. RTL readiness: run `configQualifiers = "ar"` pseudo-localisation test (optional, documented). No RTL-specific layouts needed for v1; Compose handles direction automatically.

**Validation.**
- `./gradlew lintDebug` produces zero "hardcoded text" warnings.
- Spot-check: pick 5 random user-visible screens, verify all text comes from `R.string`.

### T086 - ProGuard/R8 keep rules

**Files to modify:**
- `app/proguard-rules.pro`
- `app/build.gradle.kts` (enable R8 minification in release)

**Implementation steps:**

1. Enable minification on release build:
   ```
   buildTypes {
       release {
           isMinifyEnabled = true
           isShrinkResources = true
           proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
       }
   }
   ```
2. Keep rules in `proguard-rules.pro`:
   ```
   # Hilt
   -keep class dagger.hilt.internal.** { *; }
   -keep class * extends javax.inject.Provider

   # Room entities + DAOs (reflection-accessed)
   -keep class com.krypt.app.data.** { *; }
   -keep interface com.krypt.app.data.** { *; }

   # Kotlin metadata (Compose, reflection)
   -keep @kotlin.Metadata class * { *; }
   -keepclassmembers class **$Companion { *; }

   # Compose
   -keepclassmembers class androidx.compose.** { *; }

   # Protobuf-lite
   -keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

   # AccessibilityService manifest-referenced class
   -keep class com.krypt.app.service.AppLockerAccessibilityService { *; }
   -keep class com.krypt.app.service.KryptDeviceAdminReceiver { *; }
   -keep class com.krypt.app.service.KryptWatchdogService { *; }
   -keep class com.krypt.app.receiver.PackageReceiver { *; }

   # Deep-link entry activities
   -keep class com.krypt.app.ui.guardian.GuardianActivity { *; }
   -keep class com.krypt.app.ui.main.MainActivity { *; }
   ```
3. Verify build:
   - `./gradlew :app:assembleRelease` succeeds.
   - Install + run release APK; walk through US-1 to US-7; no crashes.

**Validation.** Release APK is produced; instrumentation smoke test runs against it.

### T087 - Compose contentDescription audit

**Files to modify:**
- Various Compose screens

**Implementation steps:**

1. Grep for `Icon(` and `Image(` in Compose code.
2. For each, ensure:
   - `contentDescription = stringResource(R.string.xxx)` for meaningful icons.
   - `contentDescription = null` for pure-decoration icons (with a one-line comment explaining why).
3. Run `./gradlew :app:lintDebug` - enforce `MissingContentDescription` lint issue as error (via `app/build.gradle.kts`:`lint { error("MissingContentDescription") }`).
4. For text inputs (PIN entry), ensure `textField` has accessible labels via `Modifier.semantics { contentDescription = ... }`.

**Validation.** Lint green; TalkBack reads every interactive element when navigating.

### T088 - verifyManifest Gradle task + CI wiring

**Files to modify:**
- `app/build.gradle.kts`
- `.github/workflows/android-ci.yml` (from WP17)

**Implementation steps:**

1. Add Gradle task in `app/build.gradle.kts`:
   ```kotlin
   tasks.register("verifyManifest") {
       group = "verification"
       description = "Fails the build if INTERNET permission appears in the assembled debug APK."
       dependsOn("assembleDebug")
       doLast {
           val apk = file("build/outputs/apk/debug/app-debug.apk")
           require(apk.exists()) { "APK not found at $apk" }
           val aapt = android.sdkDirectory.resolve("build-tools").listFiles()?.lastOrNull()?.resolve("aapt")
               ?: error("aapt not found")
           val output = providers.exec {
               commandLine(aapt, "dump", "permissions", apk.absolutePath)
           }.standardOutput.asText.get()
           if (output.contains("android.permission.INTERNET")) {
               throw GradleException("Manifest declares INTERNET permission (FR-005). Audit output:\n$output")
           }
           println("OK: APK does not declare INTERNET. (Permissions audited)")
       }
   }
   tasks.named("check") { dependsOn("verifyManifest") }
   ```
2. Update CI workflow (WP17 T084) to invoke `./gradlew check` - the `verifyManifest` task now runs on every PR.
3. Document in `CONTRIBUTING.md` (new file, brief) that any PR adding INTERNET will be rejected by CI and requires explicit spec change.

**Validation.**
- `./gradlew verifyManifest` prints "OK: APK does not declare INTERNET."
- Manually add `<uses-permission android:name="android.permission.INTERNET" />` to a branch; CI fails with the prescribed error message. Revert.
- `./gradlew check` includes `verifyManifest` in its dependency graph.

## Test Strategy

- **Unit/Integration:** Existing tests; no new.
- **Lint:** `./gradlew lintDebug` must be green.
- **Release build:** must succeed with R8 enabled.
- **Manifest audit:** `./gradlew verifyManifest` is the gate.

## Definition of Done

- [ ] `./gradlew lintDebug` green (zero hardcoded-text, zero missing-contentDescription).
- [ ] Release APK builds with R8 enabled.
- [ ] Release APK installs and functions on a real device (smoke walkthrough US-1 to US-5).
- [ ] `verifyManifest` Gradle task exists and is wired into `check`.
- [ ] CI workflow invokes `./gradlew check` (implicitly including `verifyManifest`).
- [ ] Adding INTERNET permission on a test branch causes CI to fail with the expected error message.
- [ ] `CONTRIBUTING.md` added (short) documenting the INTERNET-forbidden rule.

## Risks + Edge cases

- **R8 over-shrinks.** If Room entity classes get stripped, app crashes at DB creation. Mitigation: the keep rules explicitly whitelist `com.krypt.app.data.**`. Test release APK on a device before declaring DoD met.
- **Reflection-using libraries not on keep list.** If we added a library (future) that uses reflection, its keep rules must be added. For v1 foundation, the libraries on our list have well-known keep rules.
- **lint `MissingContentDescription` false positives.** Compose lint detection is imperfect; may flag correctly-annotated `contentDescription = null`. Suppress with a specific `@SuppressLint("ContentDescription")` per-site if justified.
- **`aapt` not on PATH on GitHub runners.** The default `actions/setup-java` doesn't include Android SDK. Use `android-actions/setup-android@v3` to install build-tools including `aapt`; adjust workflow.
- **Release signing.** Not in WP scope (for v1 we ship debug-signed sideload APKs). Release-signing config is TODO post-v1.

## Reviewer Guidance

- Run `./gradlew lintDebug` and paste summary. Zero warnings.
- Run `./gradlew assembleRelease` and install the release APK on a device - does it crash? Does it work through US-3?
- Inspect `proguard-rules.pro` for missing keeps (any class referenced by name in AndroidManifest.xml must have an explicit `-keep`).
- Verify `verifyManifest` task is listed under `./gradlew tasks --group verification`.

## Next command

```
polaris implement WP18 --base WP17
```

## Activity Log

- 2026-04-24T08:08:21Z -- claude -- lane=doing -- d
- 2026-04-24T08:10:43Z -- claude -- lane=testing -- t
- 2026-04-24T08:10:50Z -- claude -- lane=for_review -- r
- 2026-04-24T08:10:58Z -- claude -- lane=done -- Final WP on main
- 2026-04-24T10:59:49Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
