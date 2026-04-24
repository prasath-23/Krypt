---
work_package_id: WP17
lane: "done"
dependencies: [WP15]
subtasks: [T081, T082, T083, T084]
test_status: required
test_file: tests/e2e/WP17-wp17-e2e-tests.spec.js
agent: "claude"
reviewed_by: "Prasath Kumar K"
review_status: "approved"
---

# WP17 - E2E tests + manual device script + docs + CI workflow

## Objective

Feature-scoped regression suite under `tests/krypt-app-locker/`. One automated end-to-end on-device test covering the Subject-Guardian round-trip (minus real external messenger). A detailed manual test script for the inherently manual bits (overlay timing, overlay dismissal resistance, uninstall attempts, OEM battery survival). A security-audit checklist. A basic GitHub Actions CI workflow for unit tests and lint.

## Context

- **Spec SC-001 (latency), SC-002 (network egress), SC-004 (OEM battery), SC-005 (install/notify), SC-006 (uninstall block), SC-007 (round-trip time), SC-008 (manifest audit).**
- **Plan "Phase 2 - Task Planning Approach":** "E2E tests required for every feature. Each WP must include test scenarios."

## Subtasks

### T081 - EndToEndUnlockRoundTripTest (androidTest)

**Files to create:**
- `tests/krypt-app-locker/e2e/EndToEndUnlockRoundTripTest.kt`
- Supporting fixture `tests/krypt-app-locker/e2e/KryptTestFixtures.kt`

**Implementation steps:**

1. This test simulates BOTH Subject and Guardian roles on a single device + emulator pair, OR on a single device by flipping internal state between the two phases. Single-device path is simpler.
2. Fixtures:
   - `setupPairedKrypt()` - programmatically insert a `GuardianPairing` row with a known pubSalt; insert a known `K_pair` into `KPairStore`; insert a known PIN hash; mark onboarding_complete=true.
   - `installTestPackage()` - seed `LockedAppsRepository` with "com.krypt.testpkg" locked.
3. Test flow:
   - **Phase A (Subject side):** Simulate overlay shown for "com.krypt.testpkg" (manually call `OverlayManager.show` or drive the accessibility service via `ev.packageName = "com.krypt.testpkg"`). Call "Ask Guardian" which builds a `krypt://request` URL.
   - **Phase B (Guardian simulation):** Take the request URL; call `GuardianRequestViewModel.verifyPinAndBuildApproval(parsedRequest, fixedPin)`; obtain `krypt://approve` URL.
   - **Phase C (Subject consume):** Launch GuardianActivity with the approve URL via `Intent(ACTION_VIEW)`.
   - Assert: within 5 seconds, `unlockGrantRepo.activeGrantForPackage("com.krypt.testpkg", now)` is non-null AND `OverlayManager.isShowingForTest() == false`.
4. Also verify SC-007 time: measure wall-clock from request-URL generation to overlay dismissal; assert <60s (generous; actual target is much lower).

**Validation.** Runs on a single connected device; ~10-15 seconds per run.

### T082 - Manual device-test script

**Files to create:**
- `tests/krypt-app-locker/docs/manual-test-script.md`

**Implementation steps:**

Document the full walkthrough covering every user scenario + non-automatable success criteria. Include measurement recipes:

1. **SC-001 latency measurement.**
   ```
   # Terminal 1
   adb logcat -s KryptA11yLatency:D
   # Phone: launch a locked app 20 times
   # Copy the "show_ms=" values, compute p95 in spreadsheet
   # Pass if p95 <= 200 ms
   ```
2. **SC-002 network-egress audit.**
   ```
   # Install Krypt on a device behind a proxy (mitmproxy, Burp). Configure the proxy as system proxy.
   # Walk through US-1 to US-7 over 72 hours.
   # Pass if proxy logs show zero connections originating from UID of Krypt.
   ```
3. **SC-004 OEM battery-survival.**
   ```
   # Install on Pixel, Samsung, Xiaomi.
   # Complete onboarding including OEM-specific battery whitelist.
   # Leave screen off, phone on charger, for 24 hours.
   # Wake, launch a known-locked app.
   # Pass if overlay appears (subjective: felt instantaneous).
   ```
4. **SC-005 20-install bulk test.**
   ```
   # Batch install 20 APKs:
   for apk in *.apk; do adb install "$apk"; done
   # Count notifications in shade; count locked_apps rows.
   # Pass if 20/20 both.
   ```
5. **SC-006 uninstall-block.**
   ```
   # Follow docs/manual-device-admin.md.
   # Pass if dialog blocks uninstall until Device Admin revoked.
   ```
6. **SC-007 round-trip time.**
   ```
   # Two devices, side by side. Stopwatch.
   # Start timer when Subject taps "Ask Guardian".
   # Stop timer when overlay dismisses on Subject.
   # Pass if time <= 60s.
   ```
7. **US-1 through US-7 detailed walkthroughs.**
   - Each numbered, with expected observable outcomes and failure diagnostics.

**Validation.** Execute the script once fully on a physical device set; document results in the PR.

### T083 - Security-audit checklist

**Files to create:**
- `tests/krypt-app-locker/docs/security-audit-checklist.md`

**Implementation steps:**

Document-only. Items to verify:

1. **Manifest audit (SC-008).**
   ```
   aapt dump permissions app-debug.apk | grep -i INTERNET   # expect zero hits
   aapt dump permissions app-debug.apk | grep -i NETWORK    # expect zero hits (ACCESS_NETWORK_STATE etc.)
   ```
2. **Binary audit.**
   - Decompile with `apktool d app-debug.apk`; `grep -rE "http|https|okhttp|retrofit|ktor" smali/`.
   - Expected: zero hits in Krypt-owned code.
3. **Static crypto review checklist.**
   - PBKDF2 iterations >= 300,000 at calibration floor.
   - AES mode = GCM (never ECB or CBC without MAC).
   - HMAC comparison uses `MessageDigest.isEqual`.
   - No `Random()` (java.util) anywhere - only `SecureRandom` via `SecureRandomSource`.
   - K_pair storage via EncryptedSharedPreferences (Keystore-backed).
4. **Threat model checklist (from spec section 9 Assumptions).**
   - Rooted-device attacker: out of scope (confirmed).
   - PIN-coerced Guardian: out of scope (social).
   - URL interception: within scope -> defence is K_pair / AES-GCM (verify).
   - MITM during pairing: within scope -> defence is HMAC on paired reply (verify).
5. **Permission scope review.**
   - Each declared permission has a Spec FR or runtime behaviour justifying it.
   - No over-permissioning.

**Validation.** Sign off once per release.

### T084 - CI workflow (GitHub Actions)

**Files to create:**
- `.github/workflows/android-ci.yml`

**Implementation steps:**

```yaml
name: Android CI
on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v3
      - name: Run JVM unit tests + lint + manifest audit
        run: ./gradlew testDebugUnitTest lint verifyManifest
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: test-reports
          path: app/build/reports/
```

Note: `verifyManifest` is the task introduced in WP18 T088 - include as a forward reference; WP18 completion unblocks the CI's green build.

**Instrumented tests on CI.** Deferred; GitHub-hosted runners don't reliably support emulators + Accessibility Services. Document in `tests/krypt-app-locker/docs/ci-notes.md` that instrumented tests are manual-device-only for v1.

**Validation.** Push a branch; CI green.

## Test Strategy

- **Automated:** T081 + all WP* tests run via `./gradlew check` and CI.
- **Manual:** T082 is authoritative for the not-automatable slice.
- **Audit:** T083 is a release-gate document.

## Definition of Done

- [ ] `tests/krypt-app-locker/` directory exists with e2e/ + docs/.
- [ ] EndToEndUnlockRoundTripTest passes on a connected device.
- [ ] Manual test script executed once and outcomes documented.
- [ ] Security-audit checklist reviewed + signed off.
- [ ] CI workflow runs unit tests + lint + (later) manifest-audit; green on main.

## Risks + Edge cases

- **Single-device E2E fidelity.** We simulate Guardian+Subject on one device for test expedience. Document that this does NOT validate the full cross-device + messenger-transport path; that's T082 (manual).
- **Manual script rot.** As the app evolves, screens/buttons/text drift. Accept; version the script and tag it against the release.
- **CI emulator for AccessibilityService.** Worth revisiting in v2 using a pre-provisioned emulator image + snapshot (e.g. via `emulator-runner` action). Out of scope now.
- **Security audit-checklist cadence.** Intend quarterly revalidation at minimum. Document in release process.

## Reviewer Guidance

- Run T081 locally and paste output + timing in the PR.
- Skim T082 for consistency with implementation details (button labels, logcat tags, package names used as examples).
- Confirm CI workflow does NOT run `connectedDebugAndroidTest` (it would fail on GitHub runners).

## Next command

```
polaris implement WP17 --base WP15
```

## Activity Log

- 2026-04-24T08:03:59Z -- claude -- lane=doing -- d
- 2026-04-24T08:04:06Z -- claude -- lane=testing -- t
- 2026-04-24T08:06:05Z -- claude -- lane=for_review -- Branch behind; feat commit landed on main
- 2026-04-24T08:06:41Z -- claude -- lane=for_review -- direct to main
- 2026-04-24T08:07:43Z -- claude -- lane=done -- on main
- 2026-04-24T10:53:19Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
