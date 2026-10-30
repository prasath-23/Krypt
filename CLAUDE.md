# Krypt: notes for Claude Code and anyone picking this up

Krypt is an offline Android app locker. On the child's phone (the Subject) a locked app is blocked until the parent (the Guardian) approves a request on their own phone: Once, Repeating (minutes per period) or For good. The same app runs on both phones. `README.md` describes the product; `docs/HANDOFF.md` says where the work stands, what is still open, and the decisions that aren't visible in the code.

## Where the truth lives
- `polaris-specs/001-krypt-app-locker/spec.md` is the spec. Every change in behaviour is an amendment at the top (Amendment 10 is the latest), with the owner's request quoted word for word, what they chose when asked, what changed, the tests, and the FRs touched. When a later amendment changes an earlier one, the earlier text gets a note "(Amendment N: …)".
- `.polaris/AGENTS.md` has the rules for the Polaris commands in `.claude/commands/`.

## Rules (each one was the owner's call: ask before changing it)
- **No network.** The manifest must not declare INTERNET or any network-state permission, and `verifyManifest` fails the build if it does (`CONTRIBUTING.md`). Only first-party AndroidX, Kotlin and Hilt libraries.
- **Block, never overlay** (Amendment 2). Krypt's lock screen (`ui/lock/LockScreenActivity.kt`) replaces a locked app; nothing is drawn over a running app to block it. The only way to unlock is the Guardian's approval link.
- **Nothing always-on** (Amendment 5). No foreground service, no alarm, no short polling: the accessibility service is the on-demand layer. The only periodic work is the 15-minute health check, which also starts Krypt again when the phone has stopped it (Amendment 10), plus a 60-s recheck and a 1-s countdown tick that run only while a locked app with time is on screen (Amendment 7). The battery step in setup stays optional: the owner declined making it compulsory (Amendment 10). New periodic work needs the owner's OK.
- **Never time anything with one long `delay()`** (Amendment 7). Coroutine and Handler timers stop while the phone sleeps. Work the time left out again from `elapsedRealtime` and `TrustedDayClock` whenever something changes.
- **The app in front is the window with input focus** (Amendment 8). Krypt reads Android's window list, never app content. Don't go back to trusting the last window event.
- **Periods follow the clock from midnight** (Amendment 6), not rolling windows.

## Code map (`app/src/main/java/com/krypt/app/`)
| Path | What it does |
|---|---|
| `service/AppLockerAccessibilityService.kt` | The core: window events, which apps are on screen, blocking, and feeding the meter and the countdown |
| `service/AppWindows.kt` | Matches windows to apps; the app in front and every app on screen |
| `service/TimeLeftWatcher.kt`, `service/TimeLeftPill.kt` | Blocking when the time ends, and the countdown for the last 5 minutes |
| `data/daily/DailyAllowanceMeter.kt` | Repeating time: use counted per clock minute, per period |
| `data/LockerSessionStore.kt` | Running one-time unlocks |
| `deeplink/` | The `krypt://` request and approval links and their CBOR payloads |
| `ui/lock/`, `ui/guardian/`, `ui/main/` | The lock screen, the Guardian's approval screen, Home |
| `worker/AccessibilityHealthWorker.kt` | The 15-minute health check: brings Krypt back when the phone stopped it, or says what to do |
| `service/ConnectWatchdog.kt` | Restarts Krypt when Android creates its service but never connects it |

## Making a change
1. Add an amendment at the top of `spec.md` (see above), plus the notes it causes in older amendments and the FR table.
2. Tests: JVM in `app/src/test`, instrumented in `app/src/androidTest`, and the device e2e in `tests/e2e/krypt_e2e.py` with the `e2e-target` app. Where you can, write a test that fails on the build before the change.
3. Update the docs: `README.md`, `tests/e2e/README.md` (its coverage table), `tests/krypt-app-locker/docs/manual-test-script.md`, `docs/UI-DESIGN-BRIEF.md`, `docs/design-system/` (the component's README and preview, `Screens.md`, and `index.html`, which repeats them), and `research.md` or `data-model.md` beside the spec when they are affected.
4. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, so the build on a phone can be told apart (Settings > Apps > Krypt), then rebuild `apk/krypt-debug.apk`, the sideload build, from the final sources.
5. Commit messages (see `git log`): a plain sentence as the subject, and a body that names the amendment and lists what changed, the tests run and the docs touched. `CONTRIBUTING.md` asks for conventional-commit prefixes, which recent commits don't use; ask the owner which to follow.

## Build and test
You need JDK 17 (set `JAVA_HOME` if your default JDK is another) and the Android SDK (set `ANDROID_HOME`; the repo has no `local.properties`).

```sh
./gradlew testDebugUnitTest lint verifyManifest   # what CI runs; lint is slow on a cold build
./gradlew assembleDebug                            # the app and the e2e target
./gradlew connectedDebugAndroidTest                # instrumented tests, on a device
python tests/e2e/krypt_e2e.py [--quick]            # the whole e2e run, on a wiped device
python tests/e2e/run_cases.py 30e 30f              # chosen e2e cases
```

Lint reports 0 errors and 5 warnings today. Read `tests/e2e/README.md` before an e2e run: it covers the emulator setup and its quirks.

## Traps
- **Line endings.** The repo mixes CRLF and LF files, with `core.autocrlf=true`. A file committed as CRLF must stay CRLF, or git shows the whole file as rewritten, and Python's text mode quietly writes LF. Before committing, check `git diff --stat` for files that changed in full.
- **Memory.** A 3 GB emulator and Gradle together need about 8 GB free. Build first, run `./gradlew --stop`, then boot the emulator.
- **Git Bash on Windows** rewrites `/sdcard/...` arguments to adb unless `MSYS_NO_PATHCONV=1` is set.
- **A service that shows as on but isn't running** (Amendment 10). Android can keep Krypt's accessibility service switched on in Settings while nothing runs, so nothing is counted or blocked. `adb shell dumpsys accessibility | grep -E 'Bound services|Crashed services'` tells, and `adb logcat -s KryptA11y KryptDaily KryptHealth` shows what Krypt saw.

## Branches
Work lands on `dev`, and `dev` goes to `main` by pull request. Both branches are set to take changes through pull requests, and `main` is protected. CI (`.github/workflows/android-ci.yml`) runs on pull requests to `main` and on pushes to it.
