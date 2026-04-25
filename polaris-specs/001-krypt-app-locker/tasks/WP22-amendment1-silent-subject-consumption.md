---
work_package_id: WP22
lane: "done"
dependencies: [WP20]
base_branch: main
created_at: '2026-04-24T11:10:00+00:00'
subtasks: [T114, T115, T116, T117, T118]
test_status: required
test_file: tests/e2e/WP22-amendment1-silent-subject-consumption.spec.js
amendment: 1
domain: frontend-craft
---

# WP22 - Amendment 1: Silent Subject-side approval consumption + UX + replay defense

## Objective

Wire the silent approval consumption path on the Subject device. A tap on a `krypt://approve?...` link opens a tiny trampoline activity that invokes `ApprovalConsumer` (from WP20), plays the success UX (500 ms green flash + toast + haptic), and finishes. **No PIN keypad is ever shown to the Subject.** Satisfies FR-018 and FR-021.

## Context

- **Spec Amendment 1:** Section "What changed" bullets 4, 5, 6; FR-018, FR-019, FR-020, FR-021.
- **Depends on:** `ApprovalConsumer` (reworked in WP20), `MasterKeyStore` (WP19), `OverlayManager` (WP09), `SessionStore` / `UnlockGrantRepository` (WP06).

## Subtasks

### T114 - ApprovalTrampolineActivity (Subject device deep-link handler)

**Files to create:**
- `app/src/main/java/com/krypt/app/subject/ApprovalTrampolineActivity.kt`
- `app/src/main/res/layout/activity_approval_trampoline.xml` (tiny - just a `FrameLayout` for the green-flash overlay).

**Files to modify:**
- `app/src/main/AndroidManifest.xml` - add `<activity android:name=".subject.ApprovalTrampolineActivity" android:exported="true" android:theme="@style/Theme.Translucent.NoTitleBar" android:launchMode="singleTask">` with an intent-filter for `scheme=krypt, host=approve`.

**Implementation:**
1. `onCreate`: read the `Uri`, launch a `lifecycleScope.launch` that calls `approvalConsumer.consume(uri)` on `Dispatchers.Default`.
2. While waiting, show no UI (translucent theme + immediate finish on error = appears as no-op tap).
3. On `Ok(outcome)`: play success UX (T115) then `finish()`.
4. On `Err(...)`: show a toast with the matching friendly message from `contracts/approve.md` (`UnmatchedRequest` -> "This approval doesn't match a pending request.", etc.) and `finish()`.
5. Strictly no PIN field in this screen.

### T115 - Success UX (green flash + haptic + toast)

**Files to create:**
- `app/src/main/java/com/krypt/app/subject/UnlockSuccessEffect.kt`

**Implementation:**
1. `suspend fun playUnlockSuccess(activity: Activity, targetApp: String, expiresAtEpochMs: Long)`:
   - Haptic: `Vibrator#vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))` (API 29+).
   - Green flash: `FrameLayout` with `backgroundTintList = Color(0x80_35C759)` (translucent green) fades in/out over 500 ms via `ObjectAnimator` on alpha.
   - Toast: `Toast.makeText(ctx, "Unlocked $targetAppShortName until ${HH:mm}", Toast.LENGTH_SHORT).show()`. `targetAppShortName` = `PackageManager.getApplicationLabel(...)` fallback to package.
   - Also dispatch a `SessionStore.recordGrant(targetPackage, expiresAtEpochMs)` so `OverlayManager` dismisses the live overlay immediately if the locked app was in foreground.
2. Entire effect is accessibility-aware: if `AccessibilityManager.isEnabled && isTouchExplorationEnabled`, also speak "Unlocked by Guardian" via `AccessibilityEvent.TYPE_ANNOUNCEMENT`.

### T116 - OverlayManager dismiss-on-grant

**Files to modify:**
- `app/src/main/java/com/krypt/app/service/OverlayManager.kt` - already observes `SessionStore`/grants from WP09; verify it dismisses the overlay synchronously when a grant for the current foreground package is inserted. If not already wired, add a `SharedFlow<UnlockGrant>` from `UnlockGrantRepository` and subscribe.

### T117 - FR-021 assertions in OverlayManager test

**Files to modify:**
- `app/src/test/java/com/krypt/app/service/OverlayManagerTest.kt` (or androidTest equivalent) - add test: simulate grant event -> overlay removed from `WindowManager` within 200 ms.

### T118 - Unit + integration tests

**Files to create:**
- `app/src/test/java/com/krypt/app/subject/ApprovalTrampolineActivityTest.kt` - Robolectric: launch activity with a pre-built approval URI; assert `ApprovalConsumer.consume` called; asserts no `EditText` or PIN input visible in the view hierarchy (FR-018 enforcement).
- `app/src/test/java/com/krypt/app/subject/UnlockSuccessEffectTest.kt` - verify haptic triggered, toast message format.
- `app/src/test/java/com/krypt/app/deeplink/Amendment1FiveMinuteTtlTest.kt` - build request with `now=0`; consume with `now=305_000` -> `RequestExpired`.

## Definition of Done

- [ ] `ApprovalTrampolineActivity` has NO input fields (verified by `findViewById` negative test AND by view-hierarchy inspection in Robolectric).
- [ ] Valid approval tap -> overlay dismissed on target app within 200 ms; green flash + haptic + toast fire.
- [ ] Invalid approval tap -> toast with error, no overlay change, no crash.
- [ ] 5-minute TTL expiry (`now - request.iat > 300`) -> `RequestExpired` error path.
- [ ] Double-consume on same URL -> second attempt yields `UnmatchedRequest`.
- [ ] Accessibility announcement fired when TalkBack is on.
- [ ] No PIN / no input field anywhere in the Subject-side unlock flow (grepped in test with `hasContentDescription("PIN")` = false).

## Risks and Edge cases

- **Trampoline theme flicker.** A non-translucent theme would briefly flash white before finishing. MUST use `@style/Theme.Translucent.NoTitleBar` and call `finish()` in a post-UX `withContext(Main)` block, not inline.
- **Haptic on silent/DnD.** `Vibrator#vibrate` respects system haptic settings on API 33+. Acceptable - if user silenced haptics, they silenced all haptics.
- **Approval URL in clipboard.** If the Guardian pastes the URL into WhatsApp, it briefly lives in the clipboard. Android 13+ auto-clears clipboard on content-type classification; we rely on that + user operational hygiene. Out-of-scope to "clear clipboard after consumption".
- **Silent consumption + visible feedback.** FR-021 is specifically about making the success *visible* so the Subject doesn't think the link did nothing. Without the green flash + toast, a silent unlock would be disorienting.

## Activity Log

- 2026-04-24T18:38:48Z -- unknown -- lane=for_review -- WP22 Amendment 1 silent consumption + UX ready. Commit 31bf251 on 001-krypt-app-locker-WP22. ApprovalTrampolineActivity (translucent, no input surface, silent), UnlockSuccessEffect (haptic+green flash+toast+TalkBack), Theme.Krypt.Trampoline. Manifest: krypt://approve moved from GuardianActivity to ApprovalTrampolineActivity. 2 JVM tests + deferred Robolectric coverage to WP23 androidTest. Reviewer runs ./gradlew :app:testDebugUnitTest; Espresso/androidTest suite in WP23.
