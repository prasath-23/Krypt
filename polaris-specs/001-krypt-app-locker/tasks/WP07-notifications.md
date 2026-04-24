---
work_package_id: WP07
lane: "done"
dependencies: [WP01]
base_branch: 001-krypt-app-locker-WP01
base_commit: 3be5b1c4b9a96067bf5878d3e8d5ddd04b73f01c
created_at: '2026-04-24T07:23:06.168099+00:00'
subtasks: [T032, T033, T034, T035]
test_status: required
test_file: tests/e2e/WP07-wp07-notifications.spec.js
agent: "claude"
reviewed_by: "Prasath Kumar K"
review_status: "approved"
---

# WP07 - Notifications: Security-Alerts channel + helper + runtime permission

## Objective

Set up the user-visible "Security Alerts" `NotificationChannel` and a `NotificationHelper` that posts the "New App Protected" alert whenever `PackageReceiver` locks a newly-installed app (FR-004). Handle runtime `POST_NOTIFICATIONS` permission on API 33+.

## Context

- **Spec FR-004:** Post local notification via Security-Alerts channel within 500 ms of lock event; body must contain package name.
- **Spec SC-005:** 20/20 installs -> 20/20 notifications.
- **Research R7:** API 33+ runtime permission request flow.

## Subtasks

### T032 - Channel registration

**Files to create:**
- `app/src/main/java/com/krypt/app/notifications/NotificationChannels.kt`
- `app/src/main/java/com/krypt/app/notifications/SecurityAlertsChannel.kt`

**Implementation steps:**
1. `NotificationChannels` object centralises channel IDs: `const val SECURITY_ALERTS = "krypt.security_alerts"`, `const val WATCHDOG = "krypt.watchdog"` (used later in WP16).
2. `SecurityAlertsChannel @Inject constructor(@ApplicationContext private val ctx: Context)` - single `fun ensureCreated()`:
   - On API 26+: build `NotificationChannel(SECURITY_ALERTS, "Security Alerts", IMPORTANCE_HIGH)`, `description = "Alerts when Krypt auto-locks a newly-installed app"`, `enableLights(true)`, `setShowBadge(true)`.
   - Register via `NotificationManagerCompat.from(ctx).createNotificationChannel(channel)`.
   - Idempotent (safe to call on every Application.onCreate).
3. Call `ensureCreated()` from `KryptApplication.onCreate` (update WP01's Application).

**Validation.** After first app launch, `adb shell dumpsys notification --noredact | grep -A3 "krypt.security_alerts"` shows the channel with `importance=4` (HIGH).

### T033 - NotificationHelper + "New App Protected" poster

**Files to create:**
- `app/src/main/java/com/krypt/app/notifications/NotificationHelper.kt`

**Implementation steps:**
1. `class NotificationHelper @Inject constructor(@ApplicationContext private val ctx: Context)`:
   - `fun notifyAppLocked(packageName: String, displayName: String)`:
     - Title: `"New App Protected"` (string resource).
     - Body: `"$displayName ($packageName) has been locked by default"` (matches spec wording).
     - Icon: app's launcher icon (or a purpose-built 24dp vector).
     - `setAutoCancel(true)`, `setCategory(CATEGORY_ALARM)` or `CATEGORY_STATUS`, `setPriority(PRIORITY_HIGH)` (for pre-O compat), `setVisibility(VISIBILITY_PRIVATE)` (don't show body on lockscreen).
     - Tap-intent: launches `MainActivity` with `EXTRA_PACKAGE = packageName` so MainActivity can scroll to the relevant row (HomeScreen handles the extra in WP12).
     - Notification ID: `"krypt-lock-$packageName".hashCode()` so repeated installs of the same package replace rather than stack.
     - Post via `NotificationManagerCompat.from(ctx).notify(id, notification)`.
   - Gate on permission: `if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, POST_NOTIFICATIONS) != GRANTED) { Log.w; return }`.
2. String resources for title + body with placeholders.

**Validation.** androidTest from T035.

### T034 - NotificationPermissionHelper (runtime, API 33+)

**Files to create:**
- `app/src/main/java/com/krypt/app/notifications/NotificationPermissionHelper.kt`

**Implementation steps:**
1. Object-with-functions (stateless):
   - `fun isGranted(ctx: Context): Boolean` - API <33 -> true; API 33+ -> `checkSelfPermission == GRANTED`.
   - `fun rationaleNeeded(activity: Activity): Boolean` - `ActivityCompat.shouldShowRequestPermissionRationale(POST_NOTIFICATIONS)`.
   - `fun createRequestLauncher(caller: ComponentActivity, onResult: (Boolean) -> Unit): ActivityResultLauncher<String>` - wraps `registerForActivityResult(RequestPermission()) { onResult(it) }`. Caller invokes `.launch(POST_NOTIFICATIONS)`.
2. Usage by WP12 OnboardingScreen step 4.

**Validation.** Unit test is thin (mostly Android framework calls). Compose UI test in WP12 exercises it.

### T035 - androidTest for NotificationHelper

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/notifications/NotificationHelperTest.kt`

**Implementation steps:**
1. Use `NotificationManagerCompat.from(context).activeNotifications` (requires `@RequiresPermission(POST_NOTIFICATIONS)` or skipTest on API 33 without permission).
2. Grant permission via `UiAutomation.adoptShellPermissionIdentity(POST_NOTIFICATIONS)` for the test duration.
3. Call `helper.notifyAppLocked("com.example.test", "Example")`.
4. Assert: exactly one active notification with channel ID `krypt.security_alerts`, body contains both `"com.example.test"` and `"Example"`, title equals `"New App Protected"`.
5. Duplicate call with same package: assert notification count remains 1 (replaced, not appended).

**Validation.** `./gradlew :app:connectedDebugAndroidTest --tests "*NotificationHelperTest*"` passes.

## Test Strategy

- **Unit (JVM):** minimal; helpers are thin wrappers around Android APIs.
- **Instrumented (androidTest):** T035.
- **Manual:** verify during WP08 Default-Deny walkthrough that the notification actually shows and taps through to MainActivity.

## Definition of Done

- [ ] Channel created once at Application start; verified via `dumpsys notification`.
- [ ] `NotificationHelper.notifyAppLocked` posts the expected notification; body contains the package name.
- [ ] Duplicate posts for same package replace (via hash-based ID).
- [ ] Permission helper gracefully no-ops on API <33.
- [ ] androidTest green on API 33 device + API 30 device.

## Risks + Edge cases

- **API 33 denial path.** If the user denies `POST_NOTIFICATIONS`, `notifyAppLocked` silently does nothing. This is a FR-004 violation. Mitigation: during onboarding, require the permission; if denied, block further onboarding and deep-link to system settings. (UI logic in WP12.)
- **Launcher-icon asset missing.** WP01 didn't create a launcher icon. Use `R.mipmap.ic_launcher` auto-generated by AGP; replace with designed asset later.
- **Locale-dependent body.** Body string uses placeholders `%1$s` and `%2$s` in `strings.xml` for future localisation.
- **Lockscreen visibility.** `VISIBILITY_PRIVATE` hides the body on the lockscreen (shows only title + "New App Protected"). This leaks less info to someone looking at the phone. Document as intentional.

## Reviewer Guidance

- Grep for hardcoded notification strings in Kotlin; flag any not in `strings.xml`.
- Verify the tap-intent doesn't leak permissions or launch a deep-link that any third party could spoof.
- Confirm channel `IMPORTANCE_HIGH` (not MAX) - MAX is reserved for incoming-call-level interruption, inappropriate here.

## Next command

```
polaris implement WP07 --base WP01
```

## Activity Log

- 2026-04-24T07:25:15Z -- claude -- lane=doing -- n
- 2026-04-24T07:25:20Z -- claude -- lane=testing -- n
- 2026-04-24T07:25:27Z -- claude -- lane=for_review -- ready
- 2026-04-24T07:25:38Z -- claude -- lane=done -- Merged
- 2026-04-24T10:52:40Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
