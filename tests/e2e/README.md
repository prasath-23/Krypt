# Krypt end-to-end tests

`krypt_e2e.py` drives a real device or emulator over adb through the whole
product. It covers what the JVM and instrumented tests cannot:
- the accessibility service blocking other apps;
- the new-install auto-lock;
- the share sheet;
- the full Subject -> Guardian -> Subject round trip across apps.

The stand-in "newly installed app" is the `:e2e-target` module (package
`com.krypt.e2e.target`). It logs every tap as `KryptE2ETarget` "tapped", so a
case can tell that a tap on Krypt's countdown reached the app under it. It
also supports picture-in-picture and has two receivers that the run starts
with `am broadcast -n`: `EnterPip` puts the open target into
picture-in-picture (30f), and `ShowOverlay` shows a small window of the
target, titled "KryptE2EOverlay", over the app in front for 10 s without
taking focus (30g). That window needs "display over other apps": the target
declares `SYSTEM_ALERT_WINDOW`, and the run allows it with
`appops set com.krypt.e2e.target SYSTEM_ALERT_WINDOW allow`.

## Run

```sh
./gradlew :app:assembleDebug :e2e-target:assembleDebug
python tests/e2e/krypt_e2e.py            # ~50 min: includes a real 15-minute grant expiry
python tests/e2e/krypt_e2e.py --quick    # ~20 min: skips the 15-minute grant expiry, the date changes and Device Admin
```

Requirements:
- Python 3.9+.
- One device or emulator on adb, running Android 10 or later. Set
  `KRYPT_ADB_SERIAL` if it isn't `emulator-5554`.
- Use a wiped device. The script reinstalls Krypt at the start, and the last
  case activates Device Admin, after which Krypt cannot be uninstalled
  without wiping the device.
- The run reboots the device once (29b). It turns the screen off for 75 s,
  then wakes the device and dismisses the keyguard (30e), and prints how
  long the device was really suspended meanwhile. An emulator never is, so
  there 30e covers the screen going off and on during an unlock, and
  `TimeLeftWatcherTest` covers the time a sleeping phone loses on its
  timers. It also moves the
  clock (30b-30d) and switches automatic date & time (28c), always restoring
  both. It uses
  `cmd time_detector`, which needs no root. Moving the clock needs
  `set_time_state_for_tests`, which is only on recent Android versions; it
  was checked on Android 16. For 30g the run also lets the e2e target draw
  over other apps (`appops set com.krypt.e2e.target SYSTEM_ALERT_WINDOW
  allow`).
- Android 10 (checked on an API 29 emulator) runs most cases, with three
  limits. `run-as` there can't see Krypt's own process, so the cases that
  kill it (16c, 26, 28d) fail. A Chrome that was never updated is a factory
  system app, which Krypt doesn't list, so the Chrome cases (11, 13, 27, 29b)
  fail. And `uiautomator dump --windows` is missing, so dumps fall back to
  the active window, which can still be the previous app's for a while.
- When a case fails, the run saves a screenshot, the UI dump and the
  focused window for it under `<temp>/krypt_e2e_failures/<case>/`, and
  prints the path. Screenshots of the countdown also go to that folder:
  `28d_countdown.png`, `30e_countdown.png`, `30f_pip_countdown.png` and
  `31b_countdown.png`. The folder is emptied at the start of each run.
- Don't start a run shortly before local midnight: the every-day cases
  (28b-30d) assume they all run on the same calendar day.

The cases run in order, and later ones build on the state earlier ones leave.
Each prints `PASS` or `FAIL`. The run ends with a summary and exits non-zero
if anything failed.

The script reads the screen with `uiautomator dump --windows` and keeps the
focused window. A plain dump reads accessibility's "active window", which can
still be the previous window when the focus changed between dumps.

No PIN screen has a text field: a PIN is typed on Krypt's own keypad, so the
script taps its digit keys the way a person would (`enter_pin`), then checks
that the row of dots holds every digit. The Guardian screen's keypad sits
below the request and its choices, so the script scrolls before typing there.
`input text` is only used for real text fields: Home's search and the
Guardian's Custom minutes and days.

Every `uiautomator dump` briefly unbinds Krypt's accessibility service, so
the script waits for the service to be bound again before it opens an app.
On a real device nothing unbinds it. Before Amendment 8, a service bound
again only learned which app is in front at the next window change, so an
app window that arrived during a dump wasn't blocked until then. That is why
the run sets Chrome up to skip its first-run screens (case 01): they arrive
late, as a second activity. Since Amendment 8 a service bound again asks
Android which window has focus, so it knows the app in front at once.

The countdown that shows over an app in its last 5 minutes (Amendment 9) is
never checked with uiautomator: a dump unbinds Krypt's accessibility
service, and the countdown goes with it. The script finds it with
`dumpsys window windows`, by its window title "KryptTimeLeft", and taps the
middle of its frame to check that the tap reaches the app under it. It finds
the target's small window ("KryptE2EOverlay", 30g) the same way. Whether the
target is in picture-in-picture (30f) comes from `dumpsys activity
activities`, where its task's windowing mode reads "mode=pinned"
(`target_in_pip()`).

## What it covers

| Area | Positive cases | Negative cases |
|------|----------------|----------------|
| Auto-lock | A new install is locked and announced ("New app protected") before and after setup (01-02). Catch-up locks an app installed while protection was off (28). | |
| Blocking | A locked app shows Krypt's lock screen ("Locked."), never the app (03, 13). Back and "Back to home" go to the launcher (05, 14). Reopening from Recents is blocked (15). | If Krypt's process dies under the lock screen, the home screen shows, not the app (16c). |
| Setup | First run's "I'm protecting this phone", then the permission checklist with the mandatory permissions allowed, and Start protecting (06). PIN setup: the hand-over, "Choose a PIN" and "Once more" save the PIN (08). | Mismatched PINs can't be saved (07). |
| PIN gate | The right PIN opens Home (10). Leaving Krypt locks it again (12). | A wrong PIN is refused (09). |
| Home | The toggle locks an app (11). A Guardian unlock shows with its time left while the switch stays on (26b). | "Relock now", in the row's sheet, ends the unlock, and the app is blocked again (26c). |
| Request access | The request link is shared and the pending request saved (16). | Requesting before a PIN exists is refused (04). A request link pasted as a reply is refused (16b). |
| Guardian | The right PIN shares a reply (21). The Guardian can choose how long it unlocks the app (27: 30 minutes). | Wrong PIN (17), expired request (18), garbled request (19), legacy `krypt://pair` link (20). |
| Unlock | The approval link opens the app (23). The app opens freely during the grant, with no countdown while 15 minutes are left (24). The grant survives a process restart (26). The copy-and-paste path ("Paste reply") works (27). In a 15-minute grant's last 5 minutes the countdown shows over the app, and the app is blocked within 15 s of the end (30). A 3-minute Once unlock, typed into Custom, shows the countdown as soon as the app opens, and still ends on time after the screen was off for 75 s: the countdown shows again and a tap passes through it, Home hides it and reopening the app shows it, and the block lands within 10 s of the end, logged as "(time up)" (30e). | A tampered approval unlocks nothing (22). A replayed approval grants nothing more (25). A reboot ends every grant (29b). Setting the date back and restarting Krypt doesn't revive an ended grant (30b). |
| On screen | An app in picture-in-picture, with the launcher in front, keeps its 3-minute Once unlock and its countdown. When the time ends, Krypt closes the small window (logged as "closing a window of pkg=…: closed") and the lock screen shows within 10 s (30f). Chrome has a 2-minute Once unlock and its countdown; the target, with a 15-minute unlock of its own, shows a small window over Chrome without taking focus, as the launcher's window does during a cancelled home gesture. The countdown never goes, checked 8 times and by no second "countdown shown" in the log; Chrome stays in front and is blocked within 10 s of its end, logged as "(time up)" (30g, the reported bug: the build before Amendment 8 fails it). | |
| Health | Switching Krypt's accessibility service off posts "Krypt is off" at once, and switching it back on takes it down (28). The 6-hour health check is scheduled, and no Krypt service runs in the foreground (29); the check's own alert is tested by the instrumented `AccessibilityHealthWorkerTest`, because WorkManager won't run a periodic job early. | |
| Every day | The Guardian allows 1 minute a day for 2 days (Repeating, with Custom minutes and days; a day is the default period), and the app opens (28b). With at most today's minute left, the countdown shows over the app at once. A tap on it reaches the app, usage is still counted with it up, it shows again when the app is reopened after Krypt restarts, and it is gone over the lock screen. The minute runs out and blocks the app ("Done for today."), with "Request access" still offered; usage survives a restart of Krypt (28d) and a reboot (29b). Time under a one-time unlock doesn't count (30). | Automatic date & time off pauses daily time ("Paused.", 28c). Moving the date forward gives no fresh day (30c). A 40-day date jump and a restart of Krypt don't erase today's used-up time (30d). |
| Every few hours | The Guardian allows 1 minute an hour for 2 days on Chrome (Repeating, Every "1 hour", Custom minutes), and it opens, with the countdown over Chrome's minute of the hour. The minute runs out and blocks it: the lock screen says "Done for now." and when the time comes back ("More time at …"), with "Request access" still offered (31b). The next hour bringing the time back would mean waiting up to an hour, so the JVM tests cover it (`DailyAllowanceMeterTest`, `DailyAllowancePeriodTest`). | |
| Uninstall | Device Admin blocks uninstalling Krypt (31). | |

Instrumented tests (`./gradlew connectedDebugAndroidTest`) cover the same
screens in isolation. JVM tests (`./gradlew testDebugUnitTest`) cover the
logic. That includes two timing cases a device run can't hit reliably: a PIN
guess still counts when Krypt is closed during the check, and a reboot
restarts a PIN lockout rather than shortening it.
