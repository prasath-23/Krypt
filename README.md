# Krypt

**An offline app locker for Android. Locked apps open only when a trusted second person, the Guardian, approves.**

Krypt runs with no internet permission and no server. The request and the approval travel as links through any messenger you already use.

**Latest build:** [`apk/krypt-debug.apk`](apk/krypt-debug.apk). This is version 0.1.0-alpha, a debug build for sideloading. It needs Android 10 or later.

---

## How it works

1. **Every app is locked by default.** Apps installed after Krypt are locked as soon as they appear, and Krypt posts a notification saying so. Apps that were already on the phone can be locked from Krypt's Home list.
2. **Opening a locked app shows Krypt's lock screen instead.** The app itself never appears, and the lock screen has no PIN field.
3. **Request access.** The locked phone shares a `krypt://request` link through any chat app, SMS or email. The request expires after 5 minutes.
4. **The Guardian approves on their own phone.** They choose how much time to allow, then confirm with their PIN. Krypt shares back a `krypt://approve` link.
5. **Tapping the reply opens the app.** If a messenger doesn't make the link tappable, copy the whole message and tap **Paste reply** on the lock screen.

What the Guardian can allow:

| | |
|---|---|
| **Once** | 15 min, 30 min, 1 hour, 2 hours, or any 1 to 1440 minutes |
| **Repeating** | N minutes in each period, for 3, 7, 14 or 30 days, or any 1 to 365 days. The period is a day, or any length from 5 minutes up: every 30 minutes, every hour, every 90 minutes, every 2 hours, and so on. Periods follow the clock from midnight: every 2 hours means 00:00-02:00, 02:00-04:00, and so on. When a period doesn't divide the day, the day's last one is shorter and ends at midnight. The minutes can be used any time in a period, and each period starts with the full amount. This needs automatic date and time to be on. |
| **For good** | The app's lock is removed, as if the Guardian had turned it off in Krypt's Home list. The app stays open until someone locks it again. |

**When the time ends, the app is blocked at that moment,** even if it is open and the phone slept in between. In the app's last 5 minutes, a small countdown at the top of the screen shows how long is left ("1:59 left"). Taps pass through it to the app. This holds for an app in a small window too: split screen, a floating window or picture-in-picture. Its time counts there, and when the time ends Krypt asks Android to close the small window. A floating window that some phones keep on top of everything may refuse to close, and then stays over the lock screen.

The same app runs on both phones. On the locked phone it is the locker. On the Guardian's phone it only opens approval links.

## Who uses it

| Role | Who | What they see |
|---|---|---|
| **Subject** | Owns the locked phone, for example a child or someone who wants to focus. Doesn't know the PIN. | The lock screen and notifications |
| **Guardian** | A parent, partner or sponsor. Knows the PIN. | PIN setup and Krypt's Home list (on the locked phone), and the approval screen (on their own phone) |

## Install

**On the phone to protect:**

1. Copy [`apk/krypt-debug.apk`](apk/krypt-debug.apk) to the phone and open it. Allow installs from that source when Android asks. You can also run `adb install -r apk/krypt-debug.apk`.
2. Open Krypt and choose **I'm protecting this phone**.
3. Allow the two required items. Each one opens its page in Settings:
   - **See when a locked app opens** (Accessibility)
   - **Show the lock over apps** (display over other apps)

   Three more are optional: **Keep running in the background** (battery optimisation, for phones that stop apps in the background), **Make Krypt harder to remove** (Device admin) and **Alerts about new apps** (notifications).
4. Tap **Start protecting**, then hand the phone to the Guardian to choose a 4 to 6 digit PIN.

**On the Guardian's phone:** install the same APK. Request links then open Krypt's approval screen. If you open Krypt itself, choose **I'm a Guardian**.

## Privacy and security

- **No network access at all.** The APK declares no internet or network-state permission, and every build checks this (`verifyManifest`).
- **Krypt sees which apps are on screen, not what they show.** Its accessibility service asks Android for the list of windows and which one has focus, and matches each window to its app. It reads no app's content.
- **No PIN on the locked phone's unlock path.** PINs are always masked and never kept in saved state.
- **Wrong PINs lock the keypad out.** After three wrong tries the lockouts are 30 s, then 2 min, 10 min and 1 hour.
- **The approval screen can't be captured.** It blocks screenshots and screen recording, including its PIN sheet.
- **Bad approvals unlock nothing.** A tampered, replayed or expired approval is refused.
- **Unlocks don't outlive a restart.** A reboot ends every active one.

## Build from source

You need JDK 17 and the Android SDK (compile SDK 35).

```bash
./gradlew assembleDebug                            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lint verifyManifest    # what CI runs on every push to main
./gradlew connectedDebugAndroidTest                # instrumented tests, on a connected device
python tests/e2e/krypt_e2e.py --quick              # end-to-end suite over adb, see tests/e2e/README.md
```

The release build type has no signing configuration yet. Use the debug build to install.

## Project layout

| Path | What it holds |
|---|---|
| `app/` | The Android app: Kotlin, Jetpack Compose, Hilt, Room and WorkManager |
| `e2e-target/` | A tiny app that the device end-to-end suite locks and unlocks |
| `tests/e2e/` | The adb-driven end-to-end suite and how to run it |
| `docs/design-system/` | Design tokens, components and screens. Open `docs/design-system/index.html` to browse them. |
| `polaris-specs/` | The specs, plan, link contracts and work packages |
| `apk/` | The latest debug build |

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. Krypt must never gain network access, and it uses first-party AndroidX, Kotlin and Hilt libraries only.

Picking up the work? [docs/HANDOFF.md](docs/HANDOFF.md) says where it stands and what is still open, and [CLAUDE.md](CLAUDE.md) says how the project is run: the rules, how a change is made, and the traps.
