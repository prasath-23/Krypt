# Krypt end-to-end tests

`krypt_e2e.py` drives a real device or emulator over adb through the whole
product. It covers what the JVM and instrumented tests cannot:
- the accessibility service blocking other apps;
- the new-install auto-lock;
- the share sheet;
- the full Subject -> Guardian -> Subject round trip across apps.

The stand-in "newly installed app" is the `:e2e-target` module (package
`com.krypt.e2e.target`).

## Run

```sh
./gradlew :app:assembleDebug :e2e-target:assembleDebug
python tests/e2e/krypt_e2e.py            # ~40 min: includes a real 15-minute grant expiry
python tests/e2e/krypt_e2e.py --quick    # ~15 min: skips grant expiry, the date changes and Device Admin
```

Requirements:
- Python 3.9+.
- One device or emulator on adb, running Android 10 or later. Set
  `KRYPT_ADB_SERIAL` if it isn't `emulator-5554`.
- Use a wiped device. The script reinstalls Krypt at the start, and the last
  case activates Device Admin, after which Krypt cannot be uninstalled
  without wiping the device.
- The run reboots the device once (29b). It also moves the clock (30b-30d)
  and switches automatic date & time (28c), always restoring both. It uses
  `cmd time_detector`, which needs no root. Moving the clock needs
  `set_time_state_for_tests`, which is only on recent Android versions; it
  was checked on Android 16.
- When a case fails, the run saves a screenshot, the UI dump and the
  focused window for it under `<temp>/krypt_e2e_failures/<case>/`, and
  prints the path. The folder is emptied at the start of each run.
- Don't start a run shortly before local midnight: the every-day cases
  (28b-30d) assume they all run on the same calendar day.

The cases run in order, and later ones build on the state earlier ones leave.
Each prints `PASS` or `FAIL`. The run ends with a summary and exits non-zero
if anything failed.

The script reads the screen with `uiautomator dump --windows` and keeps the
focused window. A plain dump reads accessibility's "active window", which can
still be the previous window when the focus changed between dumps.

Every `uiautomator dump` briefly unbinds Krypt's accessibility service, so
the script waits for the service to be bound again before it opens an app.
On a real device nothing unbinds it. A service bound again only learns which
app is in front at the next window change, so an app window that arrives
during a dump isn't blocked until then. That is why the run sets Chrome up
to skip its first-run screens (case 01): they arrive late, as a second
activity.

## What it covers

| Area | Positive cases | Negative cases |
|------|----------------|----------------|
| Auto-lock | A new install is locked and announced before and after setup (01-02). Catch-up locks an app installed while protection was off (28). | |
| Blocking | A locked app shows Krypt's lock screen, never the app (03, 13). Back and "Go to home screen" go to the launcher (05, 14). Reopening from Recents is blocked (15). | If Krypt's process dies under the lock screen, the home screen shows, not the app (16c). |
| Setup | Onboarding with the mandatory permissions (06). PIN setup (08). | Mismatched PINs can't be saved (07). |
| PIN gate | The right PIN opens Home (10). Leaving Krypt locks it again (12). | A wrong PIN is refused (09). |
| Home | The toggle locks an app (11). A Guardian unlock shows with its time left while the switch stays on (26b). | "Lock now" ends the unlock, and the app is blocked again (26c). |
| Ask Guardian | The request link is shared and the pending request saved (16). | Asking before a PIN exists is refused (04). A request link pasted as an approval is refused (16b). |
| Guardian | The right PIN shares an approval (21). The Guardian can choose how long it unlocks the app (27: 30 minutes). | Wrong PIN (17), expired request (18), garbled request (19), legacy `krypt://pair` link (20). |
| Unlock | The approval link opens the app (23). The app opens freely during the grant (24). The grant survives a process restart (26). The copy-and-paste path works (27). An app still open when its grant ends is blocked (30). | A tampered approval unlocks nothing (22). A replayed approval grants nothing more (25). A reboot ends every grant (29b). Setting the date back and restarting Krypt doesn't revive an ended grant (30b). |
| Health | The accessibility health check is scheduled (29). The "protection is off" alert itself is tested by the instrumented `AccessibilityHealthWorkerTest`, because WorkManager won't run a periodic job early. | |
| Every day | The Guardian allows 1 minute a day for 2 days, and the app opens (28b). A minute of use runs out and blocks the app, with "Ask Guardian" still offered; usage survives a restart of Krypt (28d) and a reboot (29b). Time under a one-time unlock doesn't count (30). | Automatic date & time off pauses daily time (28c). Moving the date forward gives no fresh day (30c). A 40-day date jump and a restart of Krypt don't erase today's used-up time (30d). |
| Uninstall | Device Admin blocks uninstalling Krypt (31). | |

Instrumented tests (`./gradlew connectedDebugAndroidTest`) cover the same
screens in isolation. JVM tests (`./gradlew testDebugUnitTest`) cover the
logic. That includes two timing cases a device run can't hit reliably: a PIN
guess still counts when Krypt is closed during the check, and a reboot
restarts a PIN lockout rather than shortening it.
