---
work_package_id: WP08
lane: done
dependencies: []
base_branch: 001-krypt-app-locker-WP06
base_commit: a377c264b0d4577f3843618d6342347980dfde90
created_at: '2026-04-24T07:26:17.665231+00:00'
subtasks: [T036, T037, T038, T039]
agent: claude
test_status: required
test_file: tests/e2e/WP08-wp08-package-receiver.spec.js
review_status: approved
reviewed_by: Prasath Kumar K
domain: database
---

# WP08 - Package receiver (Default-Deny Engine)

## Objective

Wire the spec's second pillar: every new install is auto-locked, and a notification announces it. `PackageReceiver` listens for `ACTION_PACKAGE_ADDED`, resolves the package's display name, inserts into `LockedAppsRepository`, and triggers `NotificationHelper.notifyAppLocked`. Covers FR-003 and FR-004 end-to-end.

## Context

- **Spec FR-003, FR-004, SC-005.**
- **Plan:** `PackageReceiver` is the manifest-registered BroadcastReceiver.
- **Dependencies:** `LockedAppsRepository` (WP06), `NotificationHelper` (WP07).

## Subtasks

### T036 - PackageReceiver class + manifest registration

**Files to create:**
- `app/src/main/java/com/krypt/app/receiver/PackageReceiver.kt`

**Files to modify:**
- `app/src/main/AndroidManifest.xml`

**Implementation steps:**
1. `@AndroidEntryPoint class PackageReceiver : BroadcastReceiver()`:
   - `@Inject lateinit var repo: LockedAppsRepository`
   - `@Inject lateinit var notificationHelper: NotificationHelper`
   - `@Inject lateinit var packageManager: PackageManager` (via `@ApplicationContext -> ctx.packageManager` provider in Hilt)
   - `override fun onReceive(context: Context, intent: Intent)` - see T037/T038.
2. Manifest (FR-003 requires us to catch ALL installs including from Play Store):
   ```xml
   <receiver android:name=".receiver.PackageReceiver"
             android:exported="true">
     <intent-filter>
       <action android:name="android.intent.action.PACKAGE_ADDED" />
       <data android:scheme="package" />
     </intent-filter>
   </receiver>
   ```
   Note: `PACKAGE_ADDED` cannot be registered for via `Context.registerReceiver` on modern Android without `RECEIVER_EXPORTED`; manifest registration is the only reliable path AND it's the only path Hilt injects into.

**Validation.** After install, `adb shell dumpsys package com.krypt.app | grep -A5 PACKAGE_ADDED` shows the receiver registered for the broadcast.

### T037 - Package name extraction + replace-vs-new filter

**Implementation steps (in `PackageReceiver.onReceive`):**

1. Extract package: `val pkg = intent.data?.schemeSpecificPart ?: return`.
2. Guard replace-mode: `if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return`. Upgrades and reinstalls are **not** new installs; if a package was already locked, it stays locked; if unlocked (e.g., user manually removed from lock list), we respect that.
3. Self-guard: `if (pkg == context.packageName) return` (don't lock Krypt itself - the app's own `PACKAGE_ADDED` fires on first install).
4. Sanity log: `Log.i(TAG, "PACKAGE_ADDED pkg=$pkg")`.

**Validation.** Log line only; covered by T039 integration test.

### T038 - goAsync + coroutine scope for DB + notification

**Implementation steps:**

1. `BroadcastReceiver.onReceive` has ~10 s to return before the OS kills it. DB write + notification are fast (<200 ms typical) but we use `goAsync()` for safety and to avoid blocking the main thread:
   ```
   val pending = goAsync()
   appScope.launch {
       try {
           val displayName = resolveDisplayName(pkg)
           repo.lockNewlyInstalledApp(pkg, displayName)
           notificationHelper.notifyAppLocked(pkg, displayName)
       } finally {
           pending.finish()
       }
   }
   ```
2. `appScope` is an injected `CoroutineScope(SupervisorJob() + Dispatchers.IO)` provided by Hilt's `@ApplicationScope` (declared in the app-wide DI module - if that module doesn't exist yet, this WP introduces it: `app/src/main/java/com/krypt/app/di/AppModule.kt`).
3. `resolveDisplayName(pkg: String): String`:
   - `packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()`.
   - Fallback to `pkg` if `PackageManager.NameNotFoundException`.
4. Telemetry counter (internal only): `Log.i(TAG, "locked pkg=$pkg")`.

**Validation.** Covered by T039.

### T039 - androidTest: PackageReceiverIntegrationTest

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/receiver/PackageReceiverIntegrationTest.kt`
- `app/src/androidTest/assets/testpkg.apk` (a tiny test APK; a prebuilt "empty" APK)

**Implementation steps:**

1. Uses `HiltAndroidRule` to bootstrap Hilt in instrumented tests.
2. Replaces `LockedAppsRepository` binding with a `FakeLockedAppsRepository` via `@Module @TestInstallIn(components=[SingletonComponent::class], replaces=[DataModule::class])`.
3. Test `newInstallLocksApp`:
   - Use `UiAutomation.executeShellCommand("pm install -r /data/local/tmp/testpkg.apk")` OR - more portable - fabricate the broadcast directly:
     ```
     val intent = Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:com.krypt.testpkg"))
         .putExtra(Intent.EXTRA_REPLACING, false)
     val receiver = PackageReceiver()
     receiver.onReceive(targetContext, intent)
     ```
   - Await DB write via `repo.observeLockedApps().first { it.any { app -> app.packageName == "com.krypt.testpkg" } }`.
   - Assert notification posted via `NotificationManager.activeNotifications` (requires POST_NOTIFICATIONS adoption).
4. Test `upgradeDoesNotRelock`:
   - Seed an app in "unlocked" state (via `repo.unlockAppUntil(pkg, Long.MAX_VALUE)`).
   - Fire PACKAGE_ADDED with `EXTRA_REPLACING=true`.
   - Assert: app still unlocked (no state change).
5. Test `selfInstallIgnored`:
   - Fire PACKAGE_ADDED with `pkg = context.packageName`.
   - Assert: repo.findByPackage returns null.

**Validation.** All three scenarios green on connected device.

## Test Strategy

- **Unit (JVM):** not practical (BroadcastReceiver + PackageManager + NotificationManager are all Android framework).
- **Instrumented (androidTest):** T039 covers FR-003 and FR-004.
- **Manual:** Quickstart US-2 walkthrough.

## Definition of Done

- [ ] Manifest declares `PackageReceiver` with the correct `<data android:scheme="package" />` filter.
- [ ] `PackageReceiver.onReceive` ignores EXTRA_REPLACING, ignores self, resolves display name, inserts into repo, posts notification.
- [ ] `goAsync` pattern used; no work on the Binder thread beyond the initial extraction.
- [ ] `PackageReceiverIntegrationTest` 3/3 green.
- [ ] Manual Quickstart US-2 walkthrough succeeds on a physical device.

## Risks + Edge cases

- **Play Protect scan delay.** On some OEMs, the PACKAGE_ADDED broadcast can be delayed by the Play Protect scan (500-2000 ms). FR-004 says "within 500 ms of lock event"; measured from the broadcast firing, not from the user tapping Install. Flag in spec review if tighter latency is needed.
- **Background restrictions on API 26+.** Manifest-registered receivers for `PACKAGE_ADDED` are exempt from the background broadcast restrictions (confirmed by ApiReference - `PACKAGE_ADDED` is in the whitelist). Verify on Android 11+ that the receiver still fires when our process is not running (cold start).
- **`goAsync().finish()` must be called.** If Hilt injection fails (bug), we'd `return` before `pending` is instantiated. Ensure the happy-path `try/finally` wraps the entire DB+notification work; use `runCatching` if `@Inject` fails.
- **Display-name resolution cost.** `PackageManager.getApplicationInfo` + `loadLabel` are synchronous disk reads. Wrap in `withContext(Dispatchers.IO)` to stay off main.

## Reviewer Guidance

- Verify the receiver is `exported=true` (required for system broadcasts) and the `<data scheme="package" />` is present (else no broadcasts will be delivered).
- Trace the call chain: broadcast -> onReceive -> goAsync -> coroutine -> repo.insert -> notification.notify. No step should block the Binder thread for >100 ms.
- Confirm `EXTRA_REPLACING` guard exists and has a test.
- Confirm self-install guard exists and has a test.

## Next command

```
polaris implement WP08 --base WP07
```

## Activity Log

- 2026-04-24T07:27:52Z -- claude -- lane=doing -- d
- 2026-04-24T07:28:01Z -- claude -- lane=testing -- t
- 2026-04-24T07:28:08Z -- claude -- lane=for_review -- r
- 2026-04-24T07:28:18Z -- claude -- lane=done -- m
- 2026-04-24T10:52:43Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
- 2026-04-24T10:59:10Z – claude – lane=done – All WPs implemented and reviewed; feature accepted
