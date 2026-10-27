# Krypt --- Manual Test Script

Executes the non-automatable portions of Krypt's verification suite. Expected wall-time: ~90 minutes for a single reviewer on two real devices (Subject + Guardian).

## Device matrix

| Device | Role | Android version | OEM |
|---|---|---|---|
| Pixel 7 (API 35) | Subject | 15 | Google |
| Samsung S23 (API 34) | Subject | 14 | Samsung One UI |
| Xiaomi 13 (API 34) | Subject | 14 | Xiaomi MIUI |
| Any | Guardian | API 29+ | any |

## SC-001 --- Lock screen latency ≤ 200 ms p95

Since Amendment 2 a locked app is replaced by Krypt's lock screen (an
Activity) rather than covered by an overlay. Measure how long the lock screen
takes to be displayed after Krypt blocks the app:

```
# Terminal
adb logcat -c
adb logcat -s ActivityTaskManager:I > latency.log &

# Phone: launch a locked app 20 times, pressing Back on the lock screen each time.

grep "Displayed com.krypt.app/.ui.lock.LockScreenActivity" latency.log \
  | sed -E 's/.*\+([0-9]+)ms.*/\1/' | sort -n | awk '
  { a[NR] = $1 }
  END { print "p95 =", a[int(NR*0.95)], "ms" }
'
```

Pass if p95 ≤ 200. The locked app must never be usable in between.

## SC-002 --- Zero network egress (72-hour soak)

1. Route device through mitmproxy or Burp (install their CA).
2. Install Krypt; complete onboarding.
3. Leave running 72 hours, exercise US-1 to US-7 sporadically.
4. Filter proxy log by Krypt UID: expect zero rows.

## SC-004 --- OEM battery survival (24 hour)

1. Install Krypt on Pixel, Samsung, Xiaomi.
2. Finish setup. Since Amendment 5 the battery exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) is optional: leave it off on the Pixel. On the Samsung and Xiaomi, allow it and any OEM auto-start whitelist ("Help for Xiaomi, Samsung and others" on the battery row).
3. Pull down the notification shade: there is no ongoing Krypt notification.
4. Screen off, phone on charger, 24 h.
5. Wake, launch a known-locked app → Krypt's lock screen must appear.
6. Settings → Accessibility → Krypt Locker → off: "Krypt is off" must appear at once. Switch it back on: the alert must go away.

## SC-005 --- 20 bulk installs all auto-lock

```
for apk in /data/local/tmp/testapks/*.apk; do
  adb shell pm install -i com.krypt.app "$apk"
done
```

Verify `locked_apps` table has 20 new rows and notification shade has 20 "New App Protected" entries.

## SC-006 --- Uninstall block

Follow the flow in **app/docs/manual-device-admin.md** (shipped via WP11).

## SC-007 --- Subject-Guardian round trip < 60s

Two devices, stopwatch. Start when Subject taps "Ask Guardian", stop when the locked app opens on the Subject's device.

## Amendment 6 --- Repeating time every 30 minutes

Two phones on this build: the Subject phone, with "Set time automatically" on, and the Guardian phone. Use any locked app; Chrome below. Start just after a half hour begins (HH:00 or HH:30), so steps 1 to 7 fit in one 30-minute period. Keep the Subject phone's screen on. Times are shown in 24-hour style; a phone set to 12-hour shows "3:30 PM" and so on.

1. On the Subject phone, open Chrome, tap **Request access** and send the request to the Guardian phone.
2. On the Guardian phone, open the request and choose **Repeating**. The **Every** row shows 30 min, 1 hour, 2 hours, 4 hours, 6 hours, 12 hours, Day and Custom, with Day selected.
3. In **Every**, choose **Custom** and type `25`: the field says "Choose 5 minutes to 24 hours, like 1.5 or 1:30." and Continue is disabled. Change it to `0.5`. "For how long" now shows only 5 min, 10 min, 15 min and Custom. The note reads "Periods follow the clock from midnight: 00:00, 00:30, 01:00 and so on. Time already used in this one counts. Need more right now? Choose Once."
4. In **For how long**, choose **Custom** and type `31`: the field says "Choose 1 to 30 minutes." and Continue is disabled. Change it to `1`. The sentence reads "Chrome, 1 minute every 30 minutes for 7 days." Tap **Continue**, enter the Guardian PIN, tap **Approve and send** and send the reply back.
5. On the Subject phone, tap the reply. The toast reads "Chrome: 1 minute every 30 minutes until", then the date 6 days from today, and Chrome opens.
6. Use Chrome for a minute. Since Amendment 7 a countdown shows at the top of the screen the whole time, because less than 5 minutes are left. When the minute is used, Chrome is blocked at once. The lock screen says **Done for now.** and "You've used your 1 minute for now. More time at HH:MM. Need it sooner? Ask your Guardian.", where HH:MM is the next half hour.
7. Tap **Back to home**, open Krypt and enter the Guardian PIN. Chrome is under Locked, with "Every 30 min · back at HH:MM · until" and that same date. Tap the row: the sheet offers **Stop repeating time** and **Keep repeating time**. Tap Keep repeating time.
8. Open Chrome again: the lock screen is back, with Done for now. Leave it on screen. Within a few seconds of HH:MM it closes by itself, and the home screen shows. Nothing else is needed: no notification, no tap.
9. Open Chrome: it opens with no lock screen. In Krypt's Home it is under Open now, with "Every 30 min · 1 of 1 min left".

**A request from an older phone.** No older build is needed: remove the new word from a request by hand.
1. On the Subject phone, open another locked app, tap **Request access** and send the request to the Guardian phone.
2. On the Guardian phone, don't tap the link. Copy the whole message into a notes app, delete `,periods` from `caps=daily,forgood,periods`, and copy the edited message.
3. Open Krypt and tap **Paste a link**, within 5 minutes of the request. Choose **Repeating**: there is no **Every** row, the note is "Today counts as day 1, including time already used. Need more time today? Choose Once.", and the sentence ends "15 minutes a day for 7 days."

## Amendment 7 --- Blocked on time, with a countdown

Two phones on this build, as above. Use any locked app; Chrome below. If Chrome still has repeating time from the check above, stop it first: open Krypt, enter the Guardian PIN, tap Chrome's row and tap **Stop repeating time**. Unplug the Subject phone, so it can sleep while its screen is off. To see why an app was blocked, keep `adb logcat -s KryptA11y` running on a computer: each block is logged as "blocked pkg=com.android.chrome (time up)", with "in front", "on screen" (Amendment 8), "every-day time up" or "rechecked" in place of "time up" for the other reasons.

**The countdown, and taps through it.**
1. On the Subject phone, open Chrome, tap **Request access** and send the request. On the Guardian phone choose **Once**, then **Custom** under For how long, type `6`, approve and send the reply back. Tap the reply: Chrome opens, with no countdown yet.
2. Wait a minute. With 5 minutes left, a small dark pill appears at the top centre of the screen, just below the status bar. It reads about "5:00 left", with a teal timer icon, and counts down every second.
3. Tap the pill: the tap goes to Chrome under it, as if the pill weren't there. Scroll the page: Chrome works as before.
4. Press Home: the pill goes away. Open Chrome again: the pill is back, still counting down.
5. At 0:30 the icon turns into an orange hourglass.
6. When the time ends, Chrome is blocked at once: Krypt's lock screen replaces it, and the pill is gone. The log shows "blocked pkg=com.android.chrome (time up)".

**The screen off part way through an unlock.** Before this amendment this failed: Chrome stayed open after its time, until it was opened again.
1. Get another Once unlock for Chrome, with **Custom** `5`. Tap the reply. The toast reads "Chrome is open until HH:MM": note HH:MM. The countdown shows at once, from about 5:00.
2. Use Chrome for a minute, then press the power button. Leave the phone unplugged and still for 3 minutes.
3. Wake the phone and unlock it. Chrome is in front, and the pill shows about 1:00 left.
4. At HH:MM Chrome is blocked within a few seconds, without being touched.

**Repeating time running out while the app is open.** Start just after a half hour begins (HH:00 or HH:30).
1. Request access for Chrome again. On the Guardian phone choose **Repeating**, **Every** 30 min and **For how long** 10 min. The sentence reads "Chrome, 10 minutes every 30 minutes for 7 days." Approve and send the reply back, then tap it on the Subject phone: Chrome opens, with no countdown yet.
2. Keep Chrome open and in use. After 5 minutes the pill appears, at about "5:00 left".
3. When the 10 minutes are used, Chrome is blocked at once: the lock screen says **Done for now.** and "You've used your 10 minutes for now. More time at HH:MM. Need it sooner? Ask your Guardian.", where HH:MM is the next half hour. The pill is gone. The log shows "blocked pkg=com.android.chrome (every-day time up)" or "(time up)".

## Amendment 8 --- Apps on screen: a cancelled home gesture, and small windows

Two phones on this build, as above, with gesture navigation on the Subject phone. Use a locked app that plays video; the problem was seen in Instagram Reels. If the app still has repeating time from a check above, stop it first in Krypt's Home (**Stop repeating time**). Keep `adb logcat -s KryptA11y` running: it logs "countdown shown" each time the countdown appears, "blocked pkg=… (<why>)" for each block, and "closing a window of pkg=…: closed" or "…: can't be closed" when Krypt asks Android to close a small window.

**A home gesture started and cancelled.** Before this amendment the countdown could vanish and the time stop here, until some other app sent a window event.
1. Request access for the app. On the Guardian phone choose **Once**, then **Custom** under For how long, type `3`, approve and send the reply back. Tap the reply, and play a video in the app.
2. The countdown shows at once, from about "3:00 left". Start a stopwatch.
3. Start the home gesture: swipe up from the bottom edge until the home screen starts to show behind the app, then slide back down and let go, so the app stays. Do it a few times.
4. The countdown never goes away, and it keeps time with the stopwatch: 30 s on the stopwatch take about 30 s off the countdown. The log shows "countdown shown" only once.
5. When the time ends, the app is blocked at once. The log shows "blocked pkg=<the app> (time up)".

**An app in a small window.**
1. Get another Once unlock for the app, with **Custom** `3`, and tap the reply.
2. Put the app in a small window: picture-in-picture (in many video apps, press Home while a video plays), a floating "mini window" if the phone has one, or split screen with another app. Then use the other app, or stay on the home screen.
3. The countdown shows at the top of the screen, over whatever is in front. Three minutes are inside its last 5, so it shows from the start.
4. When the time ends, Krypt's lock screen shows. A picture-in-picture window is closed: the log shows "closing a window of pkg=<the app>: closed". A window that can't be closed ("…: can't be closed") is left under the lock screen, except a floating window that the phone keeps on top of everything: that one stays over the lock screen.
5. Repeating time in a small window counts too. Give the app **Repeating**, **Every** 30 min and **For how long** 10 min, starting just after a half hour begins, and use it only in a small window: the countdown appears after 5 minutes of use, and the app is blocked after 10.

## Automated end-to-end run

`tests/e2e/krypt_e2e.py` automates most of US-1 to US-7 on one device (see
tests/e2e/README.md). Run it before this script; the steps above cover what it
cannot (real OEM devices, two phones, long soaks).

## US-1 → US-7 walkthrough

See spec.md section 4 for the scenario text; each step's observable outcome and failure-diagnostic lives there.
