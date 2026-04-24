---
work_package_id: WP11
lane: "done"
dependencies: [WP01]
base_branch: 001-krypt-app-locker-WP01
base_commit: 3be5b1c4b9a96067bf5878d3e8d5ddd04b73f01c
created_at: '2026-04-24T07:34:06.572868+00:00'
subtasks: [T051, T052, T053, T054]
test_status: required
test_file: tests/e2e/WP11-wp11-device-admin.spec.js
agent: "claude"
reviewed_by: "Prasath Kumar K"
review_status: "approved"
---

# WP11 - Device Admin (uninstall friction)

## Objective

Implement FR-012: make uninstalling Krypt require revoking Device-Admin first. This is consumer-grade friction (not Device-Owner), so a motivated Subject can still revoke + uninstall - the value is *deterrence*, not *prevention*.

## Context

- **Spec FR-012, US-6.**
- **Plan:** Regular Device-Admin (user-revocable); Device-Owner is out of scope.

## Subtasks

### T051 - `res/xml/device_admin.xml`

**Files to create:**
- `app/src/main/res/xml/device_admin.xml`

**Implementation steps:**

```xml
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```

Rationale: The policy `force-lock` is the minimal admin-marker we need. Declaring it doesn't mean we invoke `lockNow()` - we never do. But Android requires *some* policy; `force-lock` is the most benign.

Alternatives considered:
- `watch-login` - failed-password tracking; not our use case.
- `expire-password` - password-expiry; not our use case.
- `wipe-data` - factory-reset power; absolutely not.

**Validation.** Activation dialog shows the correct policy summary.

### T052 - KryptDeviceAdminReceiver

**Files to create:**
- `app/src/main/java/com/krypt/app/service/KryptDeviceAdminReceiver.kt`

**Files to modify:**
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`

**Implementation steps:**

1. `class KryptDeviceAdminReceiver : DeviceAdminReceiver()`:
   - Overrides `onEnabled(ctx, intent)` -> log "device admin enabled".
   - Overrides `onDisableRequested(ctx, intent): CharSequence?` -> returns `ctx.getString(R.string.device_admin_disable_warning)`.
     - String: "Disabling Krypt device admin will allow anyone to uninstall Krypt and remove protection from your locked apps. Are you sure?"
   - Overrides `onDisabled(ctx, intent)` -> log "device admin disabled"; may broadcast an internal alert so the UI can show a warning banner.
2. Manifest:
   ```xml
   <receiver android:name=".service.KryptDeviceAdminReceiver"
             android:label="@string/device_admin_label"
             android:description="@string/device_admin_description"
             android:permission="android.permission.BIND_DEVICE_ADMIN"
             android:exported="true">
     <meta-data android:name="android.app.device_admin"
                android:resource="@xml/device_admin" />
     <intent-filter>
       <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
     </intent-filter>
   </receiver>
   ```
3. Strings:
   - `device_admin_label` = "Krypt Device Admin"
   - `device_admin_description` = "Required so Krypt cannot be uninstalled without first being disabled here. Krypt does not use any other device-admin privilege."
   - `device_admin_disable_warning` = see step 1.

**Validation.** Settings -> Security -> Device admin apps shows "Krypt Device Admin" with the correct description.

### T053 - DeviceAdminHelper

**Files to create:**
- `app/src/main/java/com/krypt/app/service/DeviceAdminHelper.kt`

**Implementation steps:**

1. `class DeviceAdminHelper @Inject constructor(@ApplicationContext private val ctx: Context)`:
   - `private val dpm: DevicePolicyManager by lazy { ctx.getSystemService(DevicePolicyManager::class.java) }`
   - `private val componentName: ComponentName by lazy { ComponentName(ctx, KryptDeviceAdminReceiver::class.java) }`
   - `fun isActive(): Boolean = dpm.isAdminActive(componentName)`
   - `fun createActivationIntent(explanation: String): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName); putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation) }`
2. Usage (WP12 OnboardingScreen):
   ```
   val launcher = registerForActivityResult(StartActivityForResult()) { result ->
       if (adminHelper.isActive()) /* step complete */ else /* retry / error */
   }
   launcher.launch(adminHelper.createActivationIntent(ctx.getString(R.string.device_admin_explanation)))
   ```

**Validation.** Unit test: `isActive()` mocked returns true/false; integration in WP12 UI test.

### T054 - Manual test script entry

**Files to create:**
- `app/docs/manual-device-admin.md`

**Implementation steps:**

Document the following walkthrough (to be folded into WP17's master manual script later):

```
# Device Admin manual verification (FR-012)

## Activation
1. Install Krypt fresh.
2. Complete onboarding through the "Activate Device Admin" step.
3. On the system confirmation dialog, tap "Activate".
4. Open Settings -> Security -> Device admin apps.
5. Expected: "Krypt Device Admin" appears and is checked.

## Uninstall-block verification
6. Settings -> Apps -> Krypt -> Uninstall.
7. Expected: dialog "Can't uninstall. Krypt is a device administrator. Deactivate device administrator first."
8. Tap the dialog's "Device admin settings" link (or return manually).
9. Toggle OFF "Krypt Device Admin".
10. Expected: confirmation dialog with the disable-warning text from T052.
11. Tap "Deactivate".
12. Return to Settings -> Apps -> Krypt -> Uninstall.
13. Expected: uninstall now proceeds normally.

## Re-activation
14. Reinstall Krypt.
15. Expected: onboarding prompts for Device Admin again; previous deactivation is not remembered.
```

Document as a WP17 feeder file for the master manual script.

**Validation.** Human manual walkthrough; non-automatable without root.

## Test Strategy

- **Unit (JVM):** `DeviceAdminHelper.isActive` with mock DPM; 2 lines of coverage.
- **Instrumented (androidTest):** `DeviceAdminReceiverLifecycleTest` - activate via reflection + `UiAutomation` (API 30+ only; documented as optional).
- **Manual (T054):** single authoritative test, human-executed.

## Definition of Done

- [ ] `device_admin.xml` declares `force-lock` and NOTHING else.
- [ ] `KryptDeviceAdminReceiver` implements `onDisableRequested` returning the deterrent string.
- [ ] Manifest registration correct (receiver, action, meta-data, BIND_DEVICE_ADMIN permission).
- [ ] `DeviceAdminHelper.createActivationIntent` returns a valid ACTION_ADD_DEVICE_ADMIN intent.
- [ ] Manual script (T054) executed once and documented to pass.

## Risks + Edge cases

- **Device-Owner-mode conflict.** If the test device is already provisioned as Device Owner by another app, activation may fail silently. Known limitation; documented in QuickStart troubleshooting.
- **Enterprise MDM devices.** On a fully-managed device, user cannot add another device admin. Out of scope for v1 (consumer-focused).
- **Disable path friction.** On some OEMs (Samsung One UI), the device-admin toggle is buried deep in Settings. We cannot smooth that; document in user-facing troubleshooting.
- **Deactivation while Krypt is force-crashed.** If Krypt is uninstalled via `pm uninstall --user 0` from adb, that bypasses the device-admin protection. Acceptable - adb is a threat vector we don't defend against.

## Reviewer Guidance

- Verify `device_admin.xml` contains ONLY `<force-lock />` - reject any other policy (principle of least privilege).
- Confirm `onDisableRequested` returns the deterrent string. Test by flipping the toggle manually.
- Check manifest has `android:permission="android.permission.BIND_DEVICE_ADMIN"` on the receiver - without it the OS refuses to bind.

## Next command

```
polaris implement WP11 --base WP01
```

## Activity Log

- 2026-04-24T07:35:18Z -- claude -- lane=doing -- d
- 2026-04-24T07:35:24Z -- claude -- lane=testing -- t
- 2026-04-24T07:35:31Z -- claude -- lane=for_review -- r
- 2026-04-24T07:35:42Z -- claude -- lane=done -- m
- 2026-04-24T10:52:56Z -- claude -- lane=done -- All WPs implemented and reviewed; feature accepted
- 2026-04-24T10:59:24Z – claude – lane=done – All WPs implemented and reviewed; feature accepted
