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
2. Grant `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + any OEM auto-start whitelist.
3. Screen off, phone on charger, 24 h.
4. Wake, launch a known-locked app → Krypt's lock screen must appear.

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

## Automated end-to-end run

`tests/e2e/krypt_e2e.py` automates most of US-1 to US-7 on one device (see
tests/e2e/README.md). Run it before this script; the steps above cover what it
cannot (real OEM devices, two phones, long soaks).

## US-1 → US-7 walkthrough

See spec.md section 4 for the scenario text; each step's observable outcome and failure-diagnostic lives there.
