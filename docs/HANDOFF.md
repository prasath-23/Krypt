# Handoff: where Krypt stands (2026-10-27)

Read this with `CLAUDE.md`, which says how the project is run, and the spec (`polaris-specs/001-krypt-app-locker/spec.md`), where each change is an amendment with the owner's words and choices.

## Done
| Amendment | Date | What it did |
|---|---|---|
| 1 | 2026-04-24 | Silent unlock, with the Guardian setting the PIN on the Subject's phone. The spec's status line still lists its work packages (WP19-WP23) as in flight. |
| 2 | 2026-09-26 | Locked apps are blocked by Krypt's lock screen instead of being covered by an overlay |
| 3 | 2026-09-27 | The Guardian chooses how long an unlock lasts, and can give every-day time |
| 4 | 2026-10-15 | For good: the Guardian's link can remove an app's lock |
| 5 | 2026-10-25 | Nothing runs all day but the accessibility service: no foreground service, a 6-hour health check, and an optional battery step |
| 6 | 2026-10-26 | Repeating time per period of any length, from 5 minutes to 24 hours, following the clock from midnight |
| 7 | 2026-10-26 | An open app is blocked the moment its time ends, even after the phone slept, with a countdown before it |
| 8 | 2026-10-26 | Krypt asks Android which windows are on screen. The app in front is the one with input focus, and apps in split screen, floating windows and picture-in-picture count too. |
| 9 | 2026-10-27 | The countdown starts at 5 minutes |

Amendments 1 to 6 are on `main` (PR #5 and earlier). Amendments 7 to 9 are on `dev`, in PR #6, which passed CI and is waiting for the owner to merge it.

## To check on a real phone
The emulator can't show these.
1. **A cancelled home gesture**, the bug behind Amendment 8. Give an app a few minutes, start the swipe-up home gesture, and let go without leaving the app. Then time 30 s on a stopwatch against the countdown: the two should move together.
2. **A phone's own floating "mini window".** Approve a 3-minute Once unlock and open the app in a mini window: the countdown should show and tick. At 3:00, see whether the window closed, went under the lock screen, or stayed on top. If it stayed on top, `adb logcat -s KryptA11y` shows whether Android refused to close it ("can't be closed").
3. **A real phone asleep during an unlock.** An emulator never suspends, so the clock fix in Amendment 7 is covered by `TimeLeftWatcherTest` and the manual test script, not by a device run.

## Known limits
- A floating window that the phone keeps on top of everything may refuse to close; the lock screen is then under it (Amendment 8).
- Background audio has no window, so it doesn't count as use.
- On Android 10 a closed picture-in-picture window can stay listed for a moment, so the lock screen may start a second time about 2 s after the first. It is already in front, so nothing changes on screen.

## Open findings the owner hasn't decided
Found in hard testing on Android 16 and Android 10 emulators on 2026-10-14 and 15. They were reported, but left alone because each is a product or UX call: don't fix them without the owner's OK.
- **Never-updated system apps can't be locked.** `AndroidInstalledAppsRepository.isUserApp` (in `ui/home/`) drops apps with FLAG_SYSTEM unless FLAG_UPDATED_SYSTEM_APP is also set (FR-036). On a phone where the preinstalled Chrome or YouTube was never updated, a parent can't lock them.
- **Home's search query outlives the PIN gate.** It lives in `HomeViewModel`, so after leaving Krypt and entering the PIN again the list can still show "No apps match …".
- **Sheets close when the screen turns.** For example, `appearanceOpen` in `ui/main/HomeScreen.kt` is a plain `remember`.
- **Landscape lock screen.** The pinned actions take most of the height, so "Locked." and the explanation sit below the fold.
- **The Guardian's "Sent." screen shares again** when the screen turns.
- **Protection can stay off after a reboot on an overloaded phone.** At very high load Android killed Krypt during startup ("failed to complete startup"), and the accessibility service then waited in "Pending services" until Krypt was opened by hand. Amendment 5 made startup lighter, and one retry under the same load started fine, but Hilt, Room and DataStore setup still run on the main thread.

## Decisions that aren't visible in the code
- **Blocking, not covering** (Amendment 2). Suspending apps as Device Owner (`setPackagesSuspended`) was rejected: it needs a factory reset and adb.
- **No "only listen to locked apps" filter** (`packageNames` in the accessibility config). It was offered with Amendment 5 and declined: more complexity for almost no battery gain.
- **Periods follow the clock** (Amendment 6). The owner first picked rolling 60-minute windows, then chose periods that follow the clock from midnight, like the day. Ask before changing that, the midnight alignment, or the shorter last period of a day.
- **Ask Android for the window list** (Amendment 8), chosen over staying blind and watching for activity. It is a privacy call, so ask before Krypt reads anything more from windows.
- **No website for links.** Shared links stay `krypt://`, with copy and paste. Tappable https links (Android App Links) need a site serving `/.well-known/assetlinks.json`, and the owner chose not to host one. If a hostname turns up later, the plan is `https://<host>/r#…` for requests and `/a#…` for approvals, with the data in the fragment, `autoVerify` intent filters beside the `krypt://` ones, and the e2e's `shared_text()` updated to match.
- **The countdown starts at 5 minutes** (Amendment 9; it was 2).

## Tested so far
- On `53e5792` (Amendment 9): 482 unit tests, lint (0 errors, 5 warnings), `verifyManifest`, e2e case 30e on Android 16, and CI on PR #6.
- On `f2d3520` (Amendment 8): 87 of 87 instrumented tests and all 47 e2e cases on Android 16; on Android 10, cases 01-10, 30e and 30f.
- Emulator setup and its quirks are in `tests/e2e/README.md`.
