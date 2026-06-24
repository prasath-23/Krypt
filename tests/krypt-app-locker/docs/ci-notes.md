# CI notes

## What CI runs

`./gradlew testDebugUnitTest lint verifyManifest` on every PR + `main` push.

## What CI does NOT run

Instrumented tests (`app/src/androidTest/...`). GitHub Actions emulators don't reliably support:
  - Accessibility Services (need UI Automator level shell control).
  - `WindowManager.addView` for overlays (flaky in headless emulator).
  - `DevicePolicyManager.isAdminActive` (requires real DPM).

Run these locally on a connected device before every merge:

```
./gradlew :app:connectedDebugAndroidTest
```
