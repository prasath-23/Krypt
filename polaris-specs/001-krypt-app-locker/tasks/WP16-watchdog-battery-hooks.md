---
work_package_id: WP16
lane: "done"
dependencies: [WP10]
base_branch: 001-krypt-app-locker-WP15
base_commit: 86f5a23e003d76c1e66db864e95dfd47f956afc7
created_at: '2026-04-24T07:48:45.152391+00:00'
subtasks: [T076, T077, T078, T079, T080]
test_status: required
test_file: tests/e2e/WP16-wp16-watchdog-battery-hooks.spec.js
agent: "claude"
reviewed_by: "Prasath Kumar K"
review_status: "approved"
---

# WP16 - Watchdog FGS + WorkManager heartbeat + OEM battery hooks

## Objective

Harden Krypt against OEM aggressive-kill policies. Supplementary foreground service supervises the Accessibility Service; WorkManager periodic heartbeat nags the user if Accessibility gets silently disabled; OEM-specific Settings deep-links guide users through the Xiaomi/Huawei/Oppo/Vivo/Samsung "protected apps" mazes. Implements FR-010, strengthens SC-004.

## Context

- **Spec FR-010, SC-004.**
- **Research R3 (battery survival strategy).**

## Subtasks

### T076 - KryptWatchdogService (foreground)

**Files to create:**
- `app/src/main/java/com/krypt/app/service/KryptWatchdogService.kt`

**Files to modify:**
- `app/src/main/AndroidManifest.xml` (add service declaration)
- `app/src/main/java/com/krypt/app/KryptApplication.kt` (start the service on onCreate)
- `app/src/main/java/com/krypt/app/notifications/NotificationChannels.kt` (add WATCHDOG channel if not already)

**Implementation steps:**

1. `@AndroidEntryPoint class KryptWatchdogService : Service()`:
   - `override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int`:
     ```
     val fgsType = if (Build.VERSION.SDK_INT >= 34) FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                   else FOREGROUND_SERVICE_TYPE_DATA_SYNC
     val notif = buildWatchdogNotification()
     startForeground(NOTIF_ID_WATCHDOG, notif, fgsType)
     return START_STICKY
     ```
   - `override fun onBind() = null`.
   - `buildWatchdogNotification()`: channel WATCHDOG, importance LOW, title "Krypt is protecting your device", body empty or "Tap to open Krypt". Tap-intent -> MainActivity.
2. `KryptApplication.onCreate`:
   ```
   val intent = Intent(this, KryptWatchdogService::class.java)
   if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
   ```
   Guarded by `if (settingsRepo.onboardingComplete)` to avoid the low-importance FGS notification on the first launch.
3. Manifest:
   ```xml
   <service android:name=".service.KryptWatchdogService"
            android:foregroundServiceType="specialUse|dataSync"
            android:exported="false" />
   ```

**Validation.** `adb shell dumpsys activity services com.krypt.app | grep Watchdog` shows service running after onboarding.

### T077 - FGS special-use subtype property

**Files to modify:**
- `app/src/main/AndroidManifest.xml`

**Implementation steps:**

Add inside the watchdog `<service>`:

```xml
<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
          android:value="accessibilityServiceSupervisor" />
```

Rationale: API 34+ requires a subtype for `specialUse`. The value "accessibilityServiceSupervisor" is human-readable and truthful.

**Validation.** `aapt dump xmltree app-debug.apk AndroidManifest.xml` shows the property; manifest compiles.

### T078 - AccessibilityHealthWorker (15-min heartbeat)

**Files to create:**
- `app/src/main/java/com/krypt/app/worker/AccessibilityHealthWorker.kt`
- `app/src/main/java/com/krypt/app/worker/WorkerScheduler.kt`

**Implementation steps:**

1. `@HiltWorker class AccessibilityHealthWorker @AssistedInject constructor(@Assisted ctx: Context, @Assisted params: WorkerParameters, private val notificationHelper: NotificationHelper, private val am: AccessibilityManager) : CoroutineWorker(ctx, params)`:
   - `override suspend fun doWork(): Result`:
     - Enumerate `am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)`.
     - Check if any entry has `serviceInfo.packageName == applicationContext.packageName`.
     - If absent: `notificationHelper.notifyAccessibilityDisabled()` (new helper method, high-importance).
     - Return `Result.success()`.
2. `WorkerScheduler`:
   - `fun schedulePeriodicHealthCheck(workManager: WorkManager)`:
     ```
     val request = PeriodicWorkRequestBuilder<AccessibilityHealthWorker>(15, TimeUnit.MINUTES)
         .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
         .build()
     workManager.enqueueUniquePeriodicWork("krypt.accessibility_health", ExistingPeriodicWorkPolicy.KEEP, request)
     ```
3. Call `schedulePeriodicHealthCheck` from `KryptApplication.onCreate` gated by onboarding-complete.
4. Hilt integration for WorkManager: `@HiltAndroidApp` class must implement `Configuration.Provider` and provide a `HiltWorkerFactory`. Add the dependency `hilt-work`.

**Validation.** `adb shell dumpsys jobscheduler | grep krypt.accessibility_health` shows the scheduled job.

### T079 - OemBatteryLinks + onboarding integration

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/main/OemBatteryLinks.kt`

**Files to modify:**
- `app/src/main/java/com/krypt/app/ui/main/OnboardingScreen.kt` (step 5 "Battery")

**Implementation steps:**

1. `object OemBatteryLinks`:
   ```
   data class OemGuide(val label: String, val intent: Intent, val helpText: String)
   fun guideForCurrentOem(pkg: String): OemGuide? = when (Build.MANUFACTURER.lowercase()) {
       "xiaomi" -> OemGuide("Xiaomi Autostart", Intent().apply { component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity") }, "On MIUI, enable Krypt under Autostart.")
       "huawei", "honor" -> OemGuide(...) /* com.huawei.systemmanager .startupmgr.ui.StartupNormalAppListActivity */
       "oppo" -> OemGuide(...) /* com.coloros.safecenter */
       "vivo" -> OemGuide(...) /* com.vivo.abe.singletask */
       "samsung" -> OemGuide(...) /* Device care battery */
       else -> null
   }
   ```
2. Safe-launch helper: `fun tryLaunch(ctx: Context, guide: OemGuide): Boolean = try { ctx.startActivity(guide.intent); true } catch (_: ActivityNotFoundException) { false }`.
3. OnboardingScreen step 5 (Battery):
   - Show the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` grant as before.
   - If a guide for current OEM is available, show a SECOND card "{MANUFACTURER} extra step" with a button to launch the OEM settings.
   - On AOSP / Google / unknown OEM, skip.
4. Fallback if `tryLaunch` returns false: show "We couldn't open that settings screen automatically. Please go to Settings -> Battery -> Krypt -> Allow auto-launch."

**Validation.** Manual test on at least one OEM device or documented in release notes.

### T080 - AccessibilityHealthWorkerTest (androidTest)

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/worker/AccessibilityHealthWorkerTest.kt`

**Implementation steps:**

1. Use `WorkManagerTestInitHelper.initializeTestWorkManager(context)`.
2. Replace `AccessibilityManager` via Hilt fake that returns an empty enabled-services list.
3. Build a `TestListenableWorkerBuilder<AccessibilityHealthWorker>` with Hilt-injected dependencies.
4. Run the worker synchronously: `worker.doWork()`.
5. Assert: notification appears with `NotificationChannels.SECURITY_ALERTS` channel (or WATCHDOG as appropriate) matching the "Accessibility is OFF" body.
6. Second test: fake returns a list with Krypt present -> notification NOT posted.

**Validation.** Both tests green.

## Test Strategy

- **Unit (JVM):** OemBatteryLinks dispatch table is pure -> test `guideForCurrentOem` with mocked `Build.MANUFACTURER` (tricky; reflection-based). Optional.
- **Instrumented:** T080 WorkManager worker test.
- **Manual:** 24-hour screen-off idle test on 3 OEM devices; documented in WP17 manual script.

## Definition of Done

- [ ] Watchdog service runs in foreground after onboarding; appears in `dumpsys activity services`.
- [ ] Watchdog notification is IMPORTANCE_LOW (minimises user annoyance).
- [ ] WorkManager periodic job registered under unique name "krypt.accessibility_health".
- [ ] AccessibilityHealthWorker correctly detects disabled state and nags.
- [ ] OemBatteryLinks covers Xiaomi, Huawei, Oppo, Vivo, Samsung; gracefully degrades on unknown OEM.
- [ ] T080 test green.
- [ ] Manual 24-hour soak on one device verifies SC-004 (overlay still fires on locked-app launch after idle).

## Risks + Edge cases

- **FGS notification visibility on Android 14.** System may elevate LOW-importance FGS notifications to visible on API 34+ to meet transparency requirements. Accept; document in user-facing help text.
- **OEM Intents break across OEM versions.** The `ComponentName`s used in `OemBatteryLinks` can change between MIUI 12 and MIUI 14. Use try/catch + fallback to generic `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` if the OEM-specific activity doesn't resolve.
- **WorkManager 15-minute minimum.** Cannot schedule more frequently. Accept; the FGS is a continuous supervisor layered on top.
- **Doze deferrability of periodic work.** Normal WorkManager jobs can be deferred in Deep Doze. Setting `setExpedited(...)` is possible but costs foreground-service-quota. We do NOT mark the health-check expedited; even delayed firing catches the "Accessibility disabled" state eventually.
- **HiltWorkerFactory integration.** Getting `@HiltWorker` + `Configuration.Provider` right is a common gotcha. Include instructions in the PR description.

## Reviewer Guidance

- Confirm FGS `foregroundServiceType` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property are both declared.
- Verify the watchdog notification channel is separate from SECURITY_ALERTS.
- Ensure `WorkerScheduler.schedulePeriodicHealthCheck` is idempotent and uses `ExistingPeriodicWorkPolicy.KEEP` to survive app upgrades.
- Run `adb shell dumpsys deviceidle step` cycle to force Doze and confirm the FGS stays alive.

## Next command

```
polaris implement WP16 --base WP10
```

## Activity Log

- 2026-04-24T07:51:48Z -- claude -- lane=doing -- d
- 2026-04-24T07:51:55Z -- claude -- lane=testing -- t
- 2026-04-24T07:52:01Z -- claude -- lane=for_review -- r
- 2026-04-24T07:52:13Z -- claude -- lane=done -- m
- 2026-04-24T10:53:16Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
