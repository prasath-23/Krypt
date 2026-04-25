---
work_package_id: WP01
lane: "for_review"
dependencies: []
base_branch: main
subtasks: [T001, T002, T003, T004, T005, T006]
test_status: required
test_file: tests/002-manual-lock-and-permission-clarity/WP01-permission-status-infrastructure.spec.js
domain: backend-logic
---

# WP01 - Permission status infrastructure

## Objective

Provide the data-layer building blocks for the new live permission indicators on the onboarding screen. After this WP, any caller can ask "is the OS-level permission for KEY granted?" and observe a `StateFlow<Map<PermissionKey, Boolean>>` that updates on `Activity.onResume` plus, where the OS supports it, listener-driven push updates.

This WP intentionally has zero UI. It is the substrate WP03 will consume.

## Context

- **Spec:** FR-022, FR-023, FR-029. Indicator visuals belong to WP03 --- only the data path is in scope here.
- **Feature 001 reuse:** Existing accessibility-service code (`AppLockerAccessibilityService`) and overlay permission helpers already query some of these states inline. We are NOT touching those call sites --- they keep working --- but we ARE introducing a single canonical `PermissionStatusProbe` so the new code paths share one definition of "is the permission granted?"
- **Lifecycle scoping:** `PermissionStateObserver` is created with an `Activity` lifecycle scope. It must NOT outlive the activity (no Application-scoped `StateFlow` collecting on a global scope).

## Subtasks

### T001 --- `PermissionKey` and `PermissionClassification` enums

**Files to create:**
- `app/src/main/java/com/krypt/app/permission/PermissionKey.kt`
- `app/src/main/java/com/krypt/app/permission/PermissionClassification.kt`

**Implementation:**
1. `enum class PermissionKey { ACCESSIBILITY, OVERLAY, BATTERY, DEVICE_ADMIN, NOTIFICATIONS }`. Add a `companion object` with `val MANDATORY: Set<PermissionKey> = setOf(ACCESSIBILITY, OVERLAY, BATTERY)` so callers can iterate without re-encoding the classification rule.
2. `enum class PermissionClassification { MANDATORY, OPTIONAL }`. Add `fun PermissionKey.classification(): PermissionClassification = if (this in PermissionKey.MANDATORY) MANDATORY else OPTIONAL` as an extension function in the same file.
3. KDoc each enum: list the OS permission backing each key (e.g., `OVERLAY` -> `SYSTEM_ALERT_WINDOW`).

### T002 --- `PermissionStatusProbe` interface + `AndroidPermissionStatusProbe`

**Files to create:**
- `app/src/main/java/com/krypt/app/permission/PermissionStatusProbe.kt`
- `app/src/main/java/com/krypt/app/permission/AndroidPermissionStatusProbe.kt`

**Implementation:**
1. `interface PermissionStatusProbe { fun statusOf(key: PermissionKey): Boolean; fun statusAll(): Map<PermissionKey, Boolean> = PermissionKey.values().associateWith { statusOf(it) } }`.
2. `class AndroidPermissionStatusProbe @Inject constructor(@ApplicationContext private val context: Context, private val deviceAdminComponent: ComponentName) : PermissionStatusProbe`:
   - `ACCESSIBILITY` → enumerate `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` and check whether any entry's `id` belongs to Krypt (string match on `context.packageName`).
   - `OVERLAY` → `Settings.canDrawOverlays(context)`.
   - `BATTERY` → `(context.getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)`. Wrap the lookup in try/catch --- some OEMs throw `SecurityException` when the package was just installed.
   - `DEVICE_ADMIN` → `(context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(deviceAdminComponent)`.
   - `NOTIFICATIONS` → `NotificationManagerCompat.from(context).areNotificationsEnabled()`.
3. The `deviceAdminComponent` is the `ComponentName` of the existing `KryptDeviceAdminReceiver` (feature 001 WP11). Provide it via Hilt (T004).

### T003 --- `PermissionStateObserver`

**Files to create:**
- `app/src/main/java/com/krypt/app/permission/PermissionStateObserver.kt`

**Implementation:**
1. `class PermissionStateObserver @Inject constructor(private val probe: PermissionStatusProbe, @ApplicationContext private val context: Context)`:
   - Internal `MutableStateFlow<Map<PermissionKey, Boolean>>` seeded by `probe.statusAll()` on construction.
   - Public `val state: StateFlow<Map<PermissionKey, Boolean>>`.
   - `fun refresh()` --- re-runs `probe.statusAll()` and emits.
2. Push-update wiring (best-effort, do NOT fail if the listener registration throws):
   - `AccessibilityManager.AccessibilityStateChangeListener` for ACCESSIBILITY. Register on `bind(lifecycleOwner)`.
   - `ContentObserver` on `Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)` as a fallback for ACCESSIBILITY (some OEMs do not fire the listener --- Xiaomi).
   - No reliable push for OVERLAY/BATTERY/DEVICE_ADMIN/NOTIFICATIONS --- these rely on `refresh()` from `onResume`.
3. `fun bind(owner: LifecycleOwner)`:
   - `owner.lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() })`.
   - On `ON_RESUME` also (re-)register the AccessibilityManager listener; on `ON_PAUSE` unregister.

### T004 --- Hilt bindings

**Files to modify (or create):**
- `app/src/main/java/com/krypt/app/di/PermissionModule.kt` (NEW).

**Implementation:**
1. `@Module @InstallIn(SingletonComponent::class) object PermissionModule`:
   - `@Provides @Singleton fun probe(@ApplicationContext ctx: Context): PermissionStatusProbe = AndroidPermissionStatusProbe(ctx, ComponentName(ctx, KryptDeviceAdminReceiver::class.java))`.
   - `@Provides @Singleton fun observer(probe: PermissionStatusProbe, @ApplicationContext ctx: Context): PermissionStateObserver = PermissionStateObserver(probe, ctx)`.
2. Confirm no duplicate binding clash with existing `CoreModule` / `SecurityModule`.

### T005 --- `AndroidPermissionStatusProbeTest` (Robolectric)

**Files to create:**
- `app/src/test/java/com/krypt/app/permission/AndroidPermissionStatusProbeTest.kt`

**Coverage:**
1. Use `RobolectricTestRunner` + `@Config(sdk = [33])` so notification permission is meaningful.
2. One assertion per `PermissionKey`. Use Robolectric `Shadows.shadowOf(...)` to flip the relevant OS state and verify the probe returns the expected Boolean.
3. Battery probe: simulate `SecurityException` from `PowerManager.isIgnoringBatteryOptimizations` and assert the probe returns `false` (not crash).

### T006 --- `PermissionStateObserverTest`

**Files to create:**
- `app/src/test/java/com/krypt/app/permission/PermissionStateObserverTest.kt`

**Coverage:**
1. Create a `FakePermissionStatusProbe` that returns programmable booleans per key.
2. Verify the initial `state.value` matches `probe.statusAll()`.
3. Drive a `LifecycleRegistry` through `ON_RESUME` and assert `state.value` re-emits after the fake probe's responses changed.
4. Verify multiple resume events do not leak listener registrations (use a counter on the fake probe).

## Definition of Done

- [ ] `PermissionStatusProbe` returns the correct boolean for every key on a Robolectric-driven test bench.
- [ ] `PermissionStateObserver.state` updates within one frame of `LifecycleEventObserver` receiving `ON_RESUME`.
- [ ] No Hilt-graph compile errors; the existing app still builds and runs.
- [ ] No new permission added to `AndroidManifest.xml`.
- [ ] `./gradlew :app:testDebugUnitTest` passes including the two new test classes.

## Risks and Edge cases

- **Xiaomi / MIUI**: The accessibility listener does not always fire when the user grants the service. The `ContentObserver` fallback covers this. Test on a real Xiaomi device during WP05 e2e.
- **`POWER_SERVICE` `SecurityException`**: Observed on some Samsung images right after install. Catch defensively.
- **Listener leak**: `PermissionStateObserver.bind` MUST unregister listeners on `ON_PAUSE` or it will leak across activity recreations.
- **Notifications API 32 vs 33**: `areNotificationsEnabled()` works on both, but the underlying setting source differs. The probe call is identical --- no API check needed.

## Reviewer guidance

- Run `./gradlew :app:testDebugUnitTest --tests "com.krypt.app.permission.*"`.
- Inspect that no UI files were modified --- this WP must touch only `app/src/main/java/com/krypt/app/permission/`, `di/PermissionModule.kt`, and the matching test directory.
- Implement command: `polaris implement WP01`.

## Activity Log

- 2026-04-25T06:05:16Z – unknown – lane=for_review – WP01: PermissionKey/Probe/StateObserver/PermissionModule + 11 unit tests. Commit fce30dd.
