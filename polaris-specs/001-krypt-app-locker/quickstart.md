# Quickstart - Krypt

Developer getting-started after foundational code exists. Covers setup, build, install, and manual verification of the seven user scenarios.

## Prerequisites

- **Android Studio** Ladybug (2024.3.x) or newer.
- **JDK 17** (bundled with Android Studio).
- **Android SDK Platform 35** installed.
- **Two physical Android devices** (or one device + one emulator running API 29+), one acting as Subject, one as Guardian. Emulator-only is possible but `AccessibilityService` behaviour on emulator differs from hardware; prefer a physical device for Subject.
- `adb` on PATH (for logs).

## Project layout

```
Krypt/
|-- app/
|   |-- build.gradle.kts
|   |-- proguard-rules.pro
|   `-- src/
|       |-- main/AndroidManifest.xml
|       |-- main/java/com/krypt/app/...
|       |-- main/res/...
|       |-- test/...       (JVM unit tests)
|       `-- androidTest/...(on-device tests)
|-- build.gradle.kts
|-- settings.gradle.kts
|-- gradle.properties
`-- polaris-specs/001-krypt-app-locker/  (spec + plan + contracts)
```

## Build

```
./gradlew :app:assembleDebug
```

First build pulls AndroidX Compose BOM, Hilt codegen, Room codegen; expect 3-5 min cold. Subsequent builds ~15 s.

## Install on Subject device

```
adb -s <subject-serial> install -r -t app/build/outputs/apk/debug/app-debug.apk
```

Then launch Krypt. You will be guided through a permission wizard:

1. **Accessibility access.** Settings -> Accessibility -> Installed apps -> Krypt -> toggle ON. Confirm the warning that Krypt can read screen content.
2. **Display over other apps.** Settings -> Apps -> Krypt -> Display over other apps -> toggle ON.
3. **Device-Admin.** Confirm the Device-Admin activation prompt.
4. **Post notifications.** Allow the runtime prompt on API 33+.
5. **Battery optimisation.** Grant "Ignore battery optimisations" via the system prompt.
6. **OEM-specific (if applicable).** Krypt will deep-link you into OEM auto-start/protected-apps settings if it detects a known OEM build fingerprint.

## Install on Guardian device

Same APK, same install command. On first launch, Krypt asks the user which role this device plays:

- "This device **holds the PIN**" -> Guardian role.
- "This device **runs the locker**" -> Subject role.

Choose Guardian. Krypt sets a Guardian role in local settings and waits for a `krypt://pair` deep-link.

## Pair Subject with Guardian

On the Subject device, open Krypt -> Onboarding -> "Pair with Guardian". Krypt generates a `krypt://pair?...` URL and opens the system Share sheet.

Send the URL to the Guardian device over any transport: copy-paste into WhatsApp, AirDrop, Bluetooth, or even in-person via a QR-display sub-screen (future; for v1, just copy the URL and paste on the Guardian device).

On the Guardian device, tap the received URL. The Guardian Popup opens:

1. Prompts the Guardian to set a master PIN (first time only).
2. Krypt-on-Guardian computes `pubSalt`, `K_pair`, and emits `krypt://paired?...` via the Share sheet.
3. Send `krypt://paired` back to the Subject device.
4. On the Subject device, tap the received URL. Pairing completes silently with a toast "Paired with <Guardian name>".

Expected total time: under 90 seconds.

## Manual verification of user scenarios

### US-2 - Default-Deny + Notification

On the Subject device, install any new app from the Play Store or via `adb install <someapk>`. Observe:

- A "Security Alerts" notification appears within ~500 ms: "New App Protected - com.example.newapp has been locked by default."
- Launching the new app from the launcher shows the Locker overlay instead of the app's UI.

### US-3 - Launching a locked app

On the Subject device, tap any app icon (Krypt's own onboarding considers all apps locked by default). Observe:

- Overlay appears within ~200 ms (stopwatch feel: instantaneous).
- Back / Home / Recents do nothing to dismiss it. Pulling the notification shade does not dismiss it.
- Rotating the device keeps the overlay fullscreen.

Log check: `adb logcat -s KryptA11y:D` should show:

```
KryptA11y: pkg=com.example.newapp  event=TYPE_WINDOW_STATE_CHANGED  latencyMs=57
KryptA11y: decision=LOCK  overlayInflated=cached  show()
```

### US-4 - Guardian approves

On the Subject overlay, tap "Ask Guardian". System Share sheet opens with a pre-filled `krypt://request?...` URL. Share to the Guardian's messenger.

On the Guardian device, tap the received URL. Guardian Popup opens showing the target package. Enter the PIN. Popup emits a Share sheet with `krypt://approve?...` URL. Share back to Subject.

### US-5 - Subject consumes approval

On the Subject device, tap the received `krypt://approve?...` URL in the messenger. Observe:

- Overlay dismisses.
- Toast: "Unlocked com.example.newapp until 10:57 (15 min)".
- Launching the app now shows its actual UI.
- Wait 15+ minutes, launch again: overlay re-appears.

### US-6 - Uninstall attempt

On the Subject device, Settings -> Apps -> Krypt -> Uninstall. Observe:

- OS dialog: "Can't uninstall. Krypt is a device administrator. Deactivate device administrator first."
- Proceed: Settings -> Security -> Device admin apps -> Krypt -> Deactivate.
- Then retry Uninstall; succeeds.

### US-7 - Fully offline

Put both devices in airplane mode. Repeat US-2 through US-5 using an offline messenger (Signal's offline message queue, Bluetooth-share, in-person copy). All flows still complete.

## Automated test entry points

```
# JVM unit tests
./gradlew :app:testDebugUnitTest

# On-device integration tests (requires connected device)
./gradlew :app:connectedDebugAndroidTest
```

Key test files:

| Test | Covers |
|------|--------|
| `app/src/test/.../crypto/KdfProviderTest.kt` | FR-008 - PBKDF2 iterations produce >=250 ms cost on CI hardware. |
| `app/src/test/.../deeplink/UnlockRequestRoundTripTest.kt` | URL build/parse round-trip invariant. |
| `app/src/test/.../deeplink/ApprovalRoundTripTest.kt` | AES-GCM encrypt/decrypt + CBOR round-trip. |
| `app/src/androidTest/.../data/KryptDatabaseTest.kt` | Room schema + DAOs in-memory. |
| `app/src/androidTest/.../ui/guardian/GuardianActivityUiTest.kt` | Deep-link entry + PIN UI + approval emit. |

Manual device script (`docs/manual-test-script.md`, to be authored in Phase 2) covers AccessibilityService latency measurement and overlay-dismissal resistance.

## Common troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Overlay doesn't appear after install | Accessibility service not enabled | Re-grant in Settings -> Accessibility. |
| Overlay appears briefly then disappears behind the app UI | Pre-inflation failed; fell back to lazy inflate and lost the <200 ms window | Check `adb logcat -s KryptOverlay:W`; file a bug with device/OEM. |
| Overlay never shown for a particular app | Package is on the Accessibility `packageNames` allowlist on that OEM | The OEM whitelist is OS-level; file user guidance. |
| Guardian Popup does not open on deep link tap | `<intent-filter>` missing or scheme typo | Inspect `AndroidManifest.xml`; verify `adb shell am start -a android.intent.action.VIEW -d 'krypt://request?...'`. |
| "Display over other apps" re-prompts every boot | Some OEMs reset; see R3 in research.md | Guide user through OEM protected-apps Settings. |

## Next steps

1. Run `/polaris.tasks` to generate Work Packages for the foundation.
2. Run `/polaris.implement WP01` to pick up the first WP.
