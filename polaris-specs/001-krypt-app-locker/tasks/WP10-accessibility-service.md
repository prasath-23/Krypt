---
work_package_id: WP10
lane: done
dependencies: []
base_branch: 001-krypt-app-locker-WP09
base_commit: f0119f03cadcf318ede53f2b23a5263f1725b5bf
created_at: '2026-04-24T07:31:28.261322+00:00'
subtasks: [T045, T046, T047, T048, T049, T050]
agent: claude
test_status: required
test_file: tests/e2e/WP10-wp10-accessibility-service.spec.js
review_status: approved
reviewed_by: Prasath Kumar K
domain: api-design
---

# WP10 - AppLockerAccessibilityService (Interception Layer)

## Objective

The critical FR-001 interception service. Observes `TYPE_WINDOW_STATE_CHANGED`, decides whether the foregrounded package is locked, and either shows the pre-inflated overlay or dismisses it. Self-whitelists `GuardianActivity` so a locked-down Guardian device can still approve incoming requests (FR-011). Logs end-to-end latency for SC-001 verification.

## Context

- **Spec FR-001, FR-002 (via OverlayManager), FR-010, FR-011, SC-001, SC-004.**
- **Research R1 (config tuning), R9 (Hilt on Service).**
- **Dependencies:** `LockedAppsRepository` (WP06), `LockerSessionStore` (WP06), `OverlayManager` (WP09).

## Subtasks

### T045 - `res/xml/accessibility_service_config.xml`

**Files to create:**
- `app/src/main/res/xml/accessibility_service_config.xml`

**Implementation steps:**
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="false"
    android:notificationTimeout="100"
    android:description="@string/accessibility_service_description"
    android:summary="@string/accessibility_service_summary" />
```

String resources:
- `accessibility_service_description` = "Krypt needs Accessibility access to see which app is being launched and cover locked apps with a Guardian-protected screen. Krypt does not read your app content and does not send any data off the device."
- `accessibility_service_summary` = "Guards your locked apps by covering them when launched."

**Validation.** File validates against the Accessibility XML schema (Android Studio does this automatically).

### T046 - AppLockerAccessibilityService class + manifest registration

**Files to create:**
- `app/src/main/java/com/krypt/app/service/AppLockerAccessibilityService.kt`

**Files to modify:**
- `app/src/main/AndroidManifest.xml` (add `<service>` declaration)

**Implementation steps:**
1. `@AndroidEntryPoint class AppLockerAccessibilityService : AccessibilityService()`:
   - `@Inject lateinit var repo: LockedAppsRepository`
   - `@Inject lateinit var sessionStore: LockerSessionStore`
   - `@Inject lateinit var overlayManager: OverlayManager`
   - `@Inject lateinit var appScope: CoroutineScope` (application-scoped)
   - Private `lockedPackagesCache: Set<String> = emptySet()` (hot-path map).
   - Private `currentJob: Job?`.
2. Manifest:
   ```xml
   <service android:name=".service.AppLockerAccessibilityService"
            android:exported="true"
            android:label="@string/accessibility_service_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
     <intent-filter>
       <action android:name="android.accessibilityservice.AccessibilityService" />
     </intent-filter>
     <meta-data android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
   </service>
   ```
3. `accessibility_service_label` string = "Krypt Locker".

**Validation.** Installing the app and toggling on Accessibility -> Krypt Locker works; `dumpsys accessibility | grep krypt` confirms it's bound.

### T047 - onServiceConnected: pre-inflate + cache bootstrap + flow subscription

**Implementation steps:**

1. `override fun onServiceConnected()`:
   ```
   super.onServiceConnected()
   overlayManager.preinflate()
   overlayManager.bindAskGuardian { pkg -> openShareSheetForRequest(pkg) } // see T048
   bootstrapLockerSession()
   subscribeToLockedAppsFlow()
   Log.i(TAG, "connected; lockedApps=${lockedPackagesCache.size}")
   ```
2. `bootstrapLockerSession()` - launches `appScope.launch { sessionStore.rebuildFrom(unlockGrantRepo) }`.
3. `subscribeToLockedAppsFlow()`:
   ```
   currentJob = appScope.launch {
     repo.observeLockedApps().collect { list ->
       lockedPackagesCache = list.filter { it.lockState == LockState.LOCKED }.map { it.packageName }.toSet()
     }
   }
   ```
4. `override fun onInterrupt()` - no-op, required by `AccessibilityService` contract.
5. `override fun onDestroy()` - cancel `currentJob`; the Service may restart and the process may or may not die.
6. `openShareSheetForRequest(pkg)` - stub in WP10; full wiring in WP14.

**Validation.** Log `"connected"` appears in logcat when user toggles Accessibility ON.

### T048 - onAccessibilityEvent hot path + self-whitelist

**Implementation steps:**

```
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val ev = event ?: return
    if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

    val pkg = ev.packageName?.toString() ?: return
    val classNameRaw = ev.className?.toString()

    // Fast exit: Krypt itself.
    if (pkg == packageName) return

    // FR-011 self-whitelist: do not overlay our own GuardianActivity.
    if (classNameRaw == GUARDIAN_ACTIVITY_CLASS) {
        overlayManager.hide()
        return
    }

    // System UI packages (launcher, keyboard, notification shade) should hide the overlay
    // so we don't flash briefly during app switches.
    if (isSystemUiPackage(pkg)) {
        overlayManager.hide()
        return
    }

    // Fast-path: is this package unlocked right now?
    if (sessionStore.isUnlockedNow(pkg)) {
        overlayManager.hide()
        return
    }

    // Locked-list check (from hot cache).
    if (pkg in lockedPackagesCache) {
        val displayName = resolveDisplayName(pkg)
        val icon = resolveIcon(pkg)
        val t0 = SystemClock.elapsedRealtime()
        overlayManager.show(pkg, displayName, icon)
        val dt = SystemClock.elapsedRealtime() - t0
        Log.d(TAG_LATENCY, "pkg=$pkg show_ms=$dt")
    } else {
        // Package is neither Krypt nor locked: hide any stale overlay.
        overlayManager.hide()
    }
}

companion object {
    const val GUARDIAN_ACTIVITY_CLASS = "com.krypt.app.ui.guardian.GuardianActivity"
    private val SYSTEM_UI_PKGS = setOf("com.android.systemui", "com.google.android.apps.nexuslauncher" /*...*/)
    private fun isSystemUiPackage(pkg: String) = pkg in SYSTEM_UI_PKGS || pkg.startsWith("com.android.")
}
```

Helper: `resolveDisplayName(pkg)` uses `PackageManager.getApplicationInfo(pkg, 0).loadLabel(pm).toString()` cached in a `LruCache<String, String>(64)`; `resolveIcon(pkg)` similarly caches `Drawable`s.

**Validation.** Smoke test: launch Settings -> overlay hides; launch a locked test app -> overlay shows within 200 ms.

### T049 - Latency logging + SC-001 verification hook

**Implementation steps:**

1. Log events with a dedicated tag `KryptA11yLatency` containing: package, event-received timestamp, overlay-shown timestamp, delta in ms.
2. For SC-001 verification, expose a developer-mode debug screen (later WP or just logcat suffices) that computes p95 over a rolling window. For foundational code, logcat suffices; a grep-based measurement script is documented in `tests/krypt-app-locker/docs/manual-test-script.md` (WP17).
3. Include pre-inflation status in logs: a warning if `overlayView` is null when `show()` is invoked (should never happen; indicates a bug).

**Validation.** Manual: tap an app 20 times, grep latency lines, verify p95 <200 ms.

### T050 - androidTest: AppLockerAccessibilityServiceTest

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/service/AppLockerAccessibilityServiceTest.kt`

**Implementation steps:**

1. Bootstrap Hilt with `FakeLockedAppsRepository`.
2. Pre-seed repository: `repo.lockNewlyInstalledApp("com.android.calculator2", "Calculator")`.
3. Programmatically enable the Accessibility Service via `UiAutomation.executeShellCommand("settings put secure enabled_accessibility_services com.krypt.app/.service.AppLockerAccessibilityService")`.
4. Launch Calculator via `Intent`; await overlay visibility by polling `overlayManager.isShowingForTest()`. Assert overlay shown within 500 ms (generous for CI emulators).
5. Launch `GuardianActivity` (from WP14 - or, for WP10 isolation, a mock activity with class name matching `GUARDIAN_ACTIVITY_CLASS`). Assert overlay hidden.
6. Launch a non-locked app (Settings). Assert overlay hidden.

**Validation.** Test requires a connected device (emulator works) with the app installed + accessibility pre-granted.

## Test Strategy

- **Unit (JVM):** none (service framework).
- **Instrumented (androidTest):** T050 covers happy path + whitelist.
- **Latency:** T049 logging, manually eyeballed; formal p95 measurement script in WP17.
- **Manual:** Quickstart US-3 full walkthrough.

## Definition of Done

- [ ] Service registers under Settings -> Accessibility; toggling it works.
- [ ] Overlay appears within ~200 ms on the target hardware (recorded latency logs present).
- [ ] Launching GuardianActivity does NOT trigger the overlay (FR-011 verified).
- [ ] Launching any system-UI package does not trigger the overlay.
- [ ] `AppLockerAccessibilityServiceTest` passes on a connected device.
- [ ] Hot-path `onAccessibilityEvent` does NOT launch a coroutine per event (pure sync lookups; coroutines only at service start).
- [ ] `appScope` is cancelled in `onDestroy`.

## Risks + Edge cases

- **API 29 `canRetrieveWindowContent=false` behaviour.** Some OEMs treat `false` as "still send events" + "do not deliver node hierarchy". We don't need the hierarchy, so `false` is correct. If events stop firing on a specific OEM (Xiaomi historical bug), document + manually test.
- **Pre-inflation race.** If `onAccessibilityEvent` fires BEFORE `onServiceConnected` finishes (shouldn't happen but defensively possible), `overlayManager.show` may crash with `overlayView == null`. Guard in `OverlayManager.show`: if not pre-inflated, synchronously inflate as fallback (pays the 20-80 ms inflation cost).
- **`ev.packageName` can be null or wrong.** Some system components emit events with `null` or `"com.android.systemui"` even when the foreground is a user app. The main filter: `if pkg is null or in SYSTEM_UI_PKGS -> ignore`.
- **Icon resolution on hot path.** `packageManager.getApplicationIcon` is a disk read; LruCache mitigates. Cache warming happens lazily on first show.
- **Self-whitelist class-name match.** Spelling errors here would cause Krypt to overlay its own Guardian Popup, breaking FR-011. Constant + test.

## Reviewer Guidance

- Verify `onAccessibilityEvent` has early-exits in the documented order (Krypt self, Guardian whitelist, system UI, session unlock, locked-list, default hide).
- Confirm NO coroutine `launch` inside `onAccessibilityEvent` (event frequency: 10-30/s on busy devices; starting coroutines per event is a perf disaster).
- Paste latency logs from a 50-launch test in the PR.
- Confirm `GUARDIAN_ACTIVITY_CLASS` fully-qualified name matches WP14's actual class name.

## Next command

```
polaris implement WP10 --base WP09
```

## Activity Log

- 2026-04-24T07:33:01Z -- claude -- lane=doing -- d
- 2026-04-24T07:33:08Z -- claude -- lane=testing -- t
- 2026-04-24T07:33:15Z -- claude -- lane=for_review -- r
- 2026-04-24T07:33:26Z -- claude -- lane=done -- m
- 2026-04-24T10:52:51Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
- 2026-04-24T10:59:20Z – claude – lane=done – All WPs implemented and reviewed; feature accepted
