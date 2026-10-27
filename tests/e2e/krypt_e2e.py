"""Krypt end-to-end tests on a real device or emulator, driven over adb.

Covers what JVM and instrumented tests cannot: the accessibility service
blocking other apps, the new-install auto-lock, the share sheet, and the
full Subject -> Guardian -> Subject round trip across apps.

Prerequisites (see tests/e2e/README.md):
  ./gradlew :app:assembleDebug :e2e-target:assembleDebug
  one device/emulator on adb (Android 10+), ideally freshly wiped.

Usage:
  python tests/e2e/krypt_e2e.py            # full run, ~50 min (waits out a 15-min grant)
  python tests/e2e/krypt_e2e.py --quick    # skip the 15-minute expiry, the date and the uninstall cases

The run is ordered: later cases build on the state earlier ones leave. It
reinstalls Krypt at the start, reboots the device once, moves the device
clock (and puts it back), and the final case activates Device Admin, after
which Krypt can only be removed by wiping the device.
"""
import argparse
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SDK = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
SERIAL = os.environ.get("KRYPT_ADB_SERIAL", "emulator-5554")

KRYPT = "com.krypt.app"
TARGET = "com.krypt.e2e.target"
CHROME = "com.android.chrome"
PIN = "2468"
APP_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
TARGET_APK = os.path.join(ROOT, "e2e-target", "build", "outputs", "apk", "debug", "e2e-target-debug.apk")

LOCK_SCREEN = f"{KRYPT}/.ui.lock.LockScreenActivity"
EVIDENCE = os.path.join(tempfile.gettempdir(), "krypt_e2e_failures")
TARGET_ACTIVITY = f"{TARGET}/.TargetActivity"
A11Y_SERVICE = f"{KRYPT}/{KRYPT}.service.AppLockerAccessibilityService"


# ------------------------------------------------------------------ adb helpers

def adb(*args, timeout=180):
    out = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    return (out.stdout + out.stderr).decode("utf-8", errors="replace")


def sh(command, timeout=180):
    return adb("shell", command, timeout=timeout)


def dump_ui():
    """Dump every window, or with an older uiautomator the active one, to /sdcard/krypt_e2e.xml."""
    if "dumped" not in sh("uiautomator dump --windows /sdcard/krypt_e2e.xml"):
        sh("uiautomator dump /sdcard/krypt_e2e.xml")


def nodes():
    """Visible UI nodes of the focused window, from uiautomator (retries while the UI is animating).

    A plain dump reads accessibility's "active window", which can still be the previous window
    when the focus changed between dumps (seen after the lock screen replaced an app, and after
    Krypt opened). So this dumps every window and keeps the focused one, which is taken from
    the window manager at the time of the dump."""
    for _ in range(8):
        dump_ui()
        raw = adb("exec-out", "cat", "/sdcard/krypt_e2e.xml")
        start = raw.find("<?xml")
        if start >= 0:
            try:
                root = ET.fromstring(raw[start: raw.rfind(">") + 1])
            except ET.ParseError:
                time.sleep(1)
                continue
            windows = root.findall(".//window")
            focused = ([w for w in windows if w.get("focused") == "true"]
                       or [w for w in windows if w.get("active") == "true"])
            tree = focused[0] if focused else root
            found = []
            for n in tree.iter("node"):
                b = [int(v) for v in re.findall(r"\d+", n.get("bounds", ""))]
                if len(b) == 4:
                    found.append({
                        "text": n.get("text", ""), "desc": n.get("content-desc", ""),
                        "pkg": n.get("package", ""), "enabled": n.get("enabled") == "true",
                        "clickable": n.get("clickable") == "true", "checked": n.get("checked") == "true",
                        "checkable": n.get("checkable") == "true",
                        "class": n.get("class", ""),
                        "x": (b[0] + b[2]) // 2, "y": (b[1] + b[3]) // 2, "bounds": b,
                    })
            return found
        time.sleep(1)
    return []


def matching(pattern):
    rx = re.compile(pattern, re.I | re.S)
    return [n for n in nodes() if rx.search(n["text"]) or rx.search(n["desc"])]


def screen_texts():
    """The texts on screen, for a failure message."""
    return [n["text"] for n in nodes() if n["text"]]


def wait_text(pattern, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = matching(pattern)
        if found:
            return found
        time.sleep(1.5)
    return []


def tap_text(pattern, timeout=20, index=0):
    found = wait_text(pattern, timeout)
    if len(found) <= index:
        raise Check(f"no '{pattern}' on screen to tap")
    n = found[index]
    sh(f"input tap {n['x']} {n['y']}")
    time.sleep(1)
    return n


def keyboard_shown():
    return "mInputShown=true" in sh("dumpsys input_method")


def type_text(text):
    """Type into the focused text field (Home's search, the Guardian's Custom minutes and days)
    and hide the keyboard. PINs go through enter_pin: no PIN screen has a text field.

    BACK only while the keyboard is up: with a hardware keyboard (or an emulator set up with
    one) no on-screen keyboard appears, and BACK would leave the screen instead."""
    sh(f"input text {text}")
    # The keyboard may put itself away after hardware-key input (as Gboard does on an emulator
    # with a hardware keyboard). Let that finish first, or BACK can land on the app instead.
    time.sleep(1.5)
    if keyboard_shown():
        sh("input keyevent KEYCODE_BACK")  # hide the keyboard
        time.sleep(0.5)


def keypad_keys():
    """Krypt's own PIN keypad on screen, as {digit: node}. Each key is a clickable node without
    text, with its digit as a child text node that is not clickable itself; tapping the digit's
    centre lands inside the key. (The Delete key is the one with content-desc "Delete".)
    Text fields are left out: the Guardian's Custom minutes and days can hold one digit too, and
    they come before the keypad on the page."""
    keys = {}
    for n in nodes():
        if n["pkg"] == KRYPT and re.fullmatch(r"[0-9]", n["text"]) and n["class"] != "android.widget.EditText":
            keys.setdefault(n["text"], n)
    return keys


def enter_pin(pin, timeout=20):
    """Type [pin] on Krypt's keypad as a person would: find the keys in one dump, then tap the
    digits in order. Waits up to [timeout] s for the keypad, and scrolls once if only part of it
    is on screen. Where the whole keypad starts below the fold (the Guardian screen), scroll first."""
    deadline = time.time() + timeout
    scrolled = False
    keys = keypad_keys()
    while set(pin) - set(keys):
        if time.time() > deadline:
            missing = ", ".join(sorted(set(pin) - set(keys)))
            raise Check(f"no keypad key for {missing} on screen; the screen shows {screen_texts()}")
        if keys and not scrolled:
            scroll_down()  # the rest of the keypad is below the fold
            scrolled = True
        else:
            time.sleep(1.5)
        keys = keypad_keys()
    for digit in pin:
        sh(f"input tap {keys[digit]['x']} {keys[digit]['y']}")
        time.sleep(0.3)
    time.sleep(0.5)
    # The row of dots reads out how many digits it holds: check that no tap was lost.
    dots = [n for n in nodes() if n["desc"].startswith("Guardian PIN, ")]
    if dots:
        expect(re.fullmatch(rf"Guardian PIN, {len(pin)} digits? entered", dots[0]["desc"]),
               f"tapped {len(pin)} keys, but the PIN entry says {dots[0]['desc']!r}")


def open_krypt_home():
    """Open Krypt and get past its PIN gate to the Home list."""
    launch(KRYPT)
    if wait_text(r"^Search apps$", 3):
        return
    enter_pin(PIN)
    tap_text(r"^Continue$")
    expect(wait_text(r"^Search apps$", 120), "Home did not open")


def search_home(text):
    tap_text(r"^Search apps$")
    type_text(text)  # types it and hides the keyboard
    time.sleep(1)


# A Home row's status. Case-sensitive, so that it isn't the LOCKED section header.
LOCKED_STATUS = r"(?-i:^Locked$)"


def home_switches():
    """The lock switches on screen. Compose marks them checkable; their class is a plain View."""
    return [n for n in nodes() if n["checkable"]]


def top_activity():
    out = sh("dumpsys activity activities")
    m = (re.search(r"topResumedActivity=ActivityRecord\{\S+ u0 (\S+)", out)
         or re.search(r"ResumedActivity: ?ActivityRecord\{\S+ u0 (\S+)", out))  # Android 10 says it this way
    return m.group(1) if m else ""


def wait_top(pattern, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        top = top_activity()
        if re.search(pattern, top):
            return top
        time.sleep(1)
    return top_activity()


def krypt_service_bound():
    return "label=Krypt Locker" in sh("dumpsys accessibility | grep 'Bound services'")


def enable_krypt_service():
    """Turn Krypt's accessibility service on and wait until it is bound. Right after Krypt is
    reinstalled, the system's cleanup for the uninstall can clear the setting a moment later,
    so check and set it again."""
    for _ in range(5):
        sh(f"settings put secure enabled_accessibility_services {A11Y_SERVICE}")
        sh("settings put secure accessibility_enabled 1")
        if wait_for(krypt_service_bound, timeout=10):
            return True
    return False


def wait_for_krypt_service():
    """Every uiautomator dump unbinds Krypt's accessibility service for a moment (a real device runs no
    uiautomator), so wait for it before doing what it has to react to."""
    deadline = time.time() + 15
    while time.time() < deadline and not krypt_service_bound():
        time.sleep(0.5)


def launch(pkg):
    """Open an app the way the launcher does. (Not with monkey: while it runs, monkey kills any app that
    stops responding, SystemUI included.)"""
    out = sh(f"cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER {pkg}")
    component = out.strip().splitlines()[-1].strip()
    expect("/" in component, f"no launcher activity for {pkg}: {out.strip()}")
    wait_for_krypt_service()
    sh(f"am start -n {component} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000")
    time.sleep(2)


def home():
    sh("input keyevent KEYCODE_HOME")
    time.sleep(1.5)


def open_link(url):
    return sh(f"am start -W -a android.intent.action.VIEW -d '{url}'")


def launcher_package():
    out = sh("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN")
    lines = [l.strip() for l in out.splitlines() if "/" in l]
    return lines[-1].split("/")[0] if lines else ""


def db(sql):
    """Query Krypt's Room database (debug build: readable through run-as)."""
    with tempfile.TemporaryDirectory() as tmp:
        for name in ("krypt.db", "krypt.db-wal", "krypt.db-shm"):
            data = subprocess.run([ADB, "-s", SERIAL, "exec-out", "run-as", KRYPT, "cat", f"databases/{name}"],
                                  capture_output=True).stdout
            if data and not data.startswith(b"cat:") and not data.startswith(b"run-as:"):
                open(os.path.join(tmp, name), "wb").write(data)
        con = sqlite3.connect(os.path.join(tmp, "krypt.db"))
        try:
            return con.execute(sql).fetchall()
        finally:
            con.close()


def krypt_notification_text():
    return sh("dumpsys notification --noredact")


PROTECTION_OFF_ID = 0x4B504F  # NotificationHelper.PROTECTION_OFF_ID


def protection_off_alert_showing():
    """Whether the "Krypt is off" alert is posted now. Only the live list's NotificationRecord lines
    count: the dump also lists notifications removed a moment ago."""
    dump = krypt_notification_text()
    expect("Current Notification Manager state" in dump, f"no notification dump: {dump[:200]!r}")
    return re.search(rf"NotificationRecord\(0x[0-9a-f]+: pkg={re.escape(KRYPT)} user=\S+ id={PROTECTION_OFF_ID} ",
                     dump) is not None


# The Guardian's PIN check runs PBKDF2 at the Subject's calibrated cost before the approval is shared.
# That takes seconds on a phone, but up to a minute on an emulator (longest right after a reboot).
APPROVAL_TIMEOUT = 120


def shared_text(timeout=20):
    """Text of the share sheet's preview (the message being shared)."""
    for n in wait_text(r"krypt://", timeout=timeout):
        if "krypt://" in n["text"]:
            return n["text"]
    return ""


def copy_from_share_sheet():
    tap_text(r"^Copy( text)?$|Copy to clipboard")


def dismiss_share_sheet():
    """Close the share sheet if it is still open (its Copy action closes it itself)."""
    time.sleep(1)
    if re.search(r"intentresolver|Chooser|Resolver", top_activity()):
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1.5)


def krypt_logcat(pattern):
    return re.findall(pattern, adb("logcat", "-d", "-s", "KryptA11y:*", "KryptNewInstall:*"))


PILL_WINDOW = "KryptTimeLeft"  # TimeLeftPill.WINDOW_TITLE


def window_frame(title):
    """The frame (left, top, right, bottom) of the window with [title] while it is on screen, else None.

    Read from the window manager, never from uiautomator: a dump unbinds Krypt's accessibility service,
    and the countdown goes with it."""
    out = sh("dumpsys window windows")
    at = out.find(f" {title}}}")
    if at < 0:
        return None
    end = out.find("Window #", at)
    block = out[at:end if end > 0 else len(out)]
    m = re.search(r"(?<![A-Za-z])(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", block)
    return tuple(int(v) for v in m.groups()) if m else (0, 0, 0, 0)


def pill_frame():
    """The countdown's window frame while it shows over an app, else None."""
    return window_frame(PILL_WINDOW)


def pill_shown():
    return pill_frame() is not None


def target_in_pip():
    """Whether the e2e target is in picture-in-picture: its task's windowing mode is "pinned"."""
    out = sh("dumpsys activity activities")
    if re.search(rf"A=\d+:{re.escape(TARGET)}\b[^\n]*mode=pinned", out):  # Android 11 and later
        return True
    # Android 10 says it of the stack, and lists the target's TaskRecord under it.
    stack = re.search(r"Stack #\d+: type=\w+ mode=pinned(.*?)(?=\n\s*Stack #|\Z)", out, re.S)
    return bool(stack and f"A={TARGET} " in stack.group(1))


def screenshot(name):
    """Keep what the screen shows now in the evidence folder, e.g. to look at the countdown."""
    os.makedirs(EVIDENCE, exist_ok=True)
    png = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    path = os.path.join(EVIDENCE, f"{name}.png")
    open(path, "wb").write(png)
    return path


def expect_taps_reach_the_app_through_the_pill():
    """Tap the middle of the countdown: the app under it must get the tap (the target logs each one)."""
    frame = pill_frame()
    expect(frame and frame[2] > frame[0], f"no countdown to tap through: {frame}")
    status_bar = window_frame("StatusBar")
    expect(not status_bar or frame[1] >= status_bar[3], f"the countdown {frame} covers the status bar {status_bar}")
    adb("logcat", "-c")
    sh(f"input tap {(frame[0] + frame[2]) // 2} {(frame[1] + frame[3]) // 2}")
    tapped = wait_for(lambda: "tapped" in adb("logcat", "-d", "-s", "KryptE2ETarget:*"), timeout=5)
    expect(tapped, f"a tap on the countdown at {frame} did not reach the app under it")


def wait_for(condition, timeout=20, step=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(step)
    return condition()


def restart_krypt():
    """Kill Krypt's process and wait for Android to start it again (for its accessibility service)."""
    pid = sh(f"pidof {KRYPT}").strip()
    expect(pid, "Krypt is not running")
    sh(f"run-as {KRYPT} kill -9 {pid}")
    # Android waits longer each time a service dies again soon after it started: 40 s was seen when 30d
    # killed Krypt twice in 8 s on an emulator short of memory.
    restarted = wait_for(lambda: sh(f"pidof {KRYPT}").strip() not in ("", pid), timeout=90)
    expect(restarted, "Krypt did not restart")
    time.sleep(5)  # service reconnects and restores grants


def reboot_device():
    boot = sh("settings get global boot_count").strip()
    adb("reboot", timeout=120)
    adb("wait-for-device", timeout=300)
    booted = wait_for(lambda: sh("getprop sys.boot_completed").strip() == "1", timeout=300, step=3)
    expect(booted, "the device did not finish booting")
    expect(sh("settings get global boot_count").strip() != boot, "the device did not reboot")
    def service_connected():
        answer_anr_dialog()  # right after a reboot, SystemUI often stops responding for a while
        return "connected" in adb("logcat", "-d", "-s", "KryptA11y:*")

    expect(wait_for(service_connected, timeout=240, step=3),
           "Krypt's accessibility service did not reconnect after the reboot")
    settle()


def answer_anr_dialog():
    """Answer an "isn't responding" dialog with Wait, if one is up. Returns whether one was."""
    if "Application Not Responding" not in sh("dumpsys window | grep 'Application Not Responding'"):
        return False
    wait_buttons = [n for n in nodes() if n["text"] == "Wait"]
    if wait_buttons:
        sh(f"input tap {wait_buttons[0]['x']} {wait_buttons[0]['y']}")
    return True


def settle(timeout=240):
    """Wait out a freshly booted device: answer "isn't responding" dialogs with Wait, dismiss the
    keyguard, and carry on once it has been calm (and Krypt's service bound) for 15 s."""
    deadline = time.time() + timeout
    calm = 0
    while calm < 3:
        expect(time.time() < deadline, "the device did not settle after the reboot")
        sh("input keyevent KEYCODE_WAKEUP")
        sh("wm dismiss-keyguard")
        anr = answer_anr_dialog()
        keyguard = "isKeyguardShowing=true" in sh("dumpsys window | grep isKeyguardShowing")
        calm = calm + 1 if not anr and not keyguard and krypt_service_bound() else 0
        time.sleep(5)


def device_time_ms():
    return int(sh("date +%s").strip()) * 1000


def set_device_time(epoch_ms):
    """Set the device clock without root, through the time detector's test hook."""
    sh("cmd time_detector set_auto_detection_enabled false")
    elapsed = int(float(sh("cat /proc/uptime").split()[0]) * 1000)
    out = sh(f"cmd time_detector set_time_state_for_tests --elapsed_realtime {elapsed} "
             f"--unix_epoch_time {epoch_ms} --user_should_confirm_time false")
    expect(abs(device_time_ms() - epoch_ms) < 60_000, f"could not set the device clock: {out.strip()}")


def restore_device_time():
    set_device_time(int(time.time() * 1000))
    sh("cmd time_detector set_auto_detection_enabled true")


_SCREEN = []


def screen_size():
    """The display's width and height in pixels (an override set with `wm size` wins)."""
    if not _SCREEN:
        sizes = re.findall(r"(\d+)x(\d+)", sh("wm size"))
        _SCREEN.append(tuple(int(v) for v in sizes[-1]) if sizes else (1080, 2400))
    return _SCREEN[0]


def scroll_down():
    """Swipe the page up by half a screen. Starts above the buttons Krypt pins to the bottom of a
    page (e.g. Approve and send), so the swipe lands on the part that scrolls."""
    width, height = screen_size()
    sh(f"input swipe {width // 2} {height * 7 // 10} {width // 2} {height // 5} 400")
    time.sleep(1)


def daily_used_ms(pkg):
    """Time [pkg] was used on the latest day with any use (minute_usage keeps it per minute of the clock)."""
    rows = db(f"select sum(usedMs) from minute_usage where packageName='{pkg}' and minute / 1440 = "
              f"(select max(minute) / 1440 from minute_usage where packageName='{pkg}')")
    return (rows[0][0] or 0) if rows else 0


def set_auto_time(on):
    sh(f"cmd time_detector set_auto_detection_enabled {'true' if on else 'false'}")
    sh(f"settings put global auto_time {1 if on else 0}")  # Android 10-11 have no time_detector command
    time.sleep(2)  # Krypt hears about it through a settings observer


def request_access_for(pkg):
    """On the child's phone: open the locked app, tap Request access, return the request link.
    While an earlier request still waits, the lock screen offers Send again instead."""
    launch(pkg)
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), f"{pkg} was not blocked; top is {top_activity()}")
    tap_text(r"^(Request access|Send again)$")
    request = re.search(r"krypt://request\?[A-Za-z0-9._~*%+=&-]+", shared_text())
    expect(request, "no request link")
    expect("caps=daily" in request.group(0), "the request doesn't say this phone takes every-day approvals")
    dismiss_share_sheet()
    return request.group(0)


def wait_guardian_form(timeout=20):
    """Wait for the Guardian's approval form. Not for its ACCESS REQUEST eyebrow: the screen for an
    expired or unreadable request has that too, and it can still be up from an earlier case."""
    if not wait_text(r"^For how long$", timeout):
        raise Check(f"the Guardian's approval form did not open; the screen shows {screen_texts()}")


def guardian_approve(pin):
    """On the Guardian's form: Continue opens the PIN sheet (step 2); type [pin] on its keypad and
    tap Approve and send. A wrong PIN leaves the sheet open, so Continue is tapped only when needed."""
    if not matching(r"^Approve and send$"):
        tap_text(r"^Continue$")  # pinned to the bottom of step 1
        expect(wait_text(r"^Approve and send$", 20), "the PIN sheet did not open")
    enter_pin(pin)  # the whole keypad is on the sheet; nothing scrolls
    tap_text(r"^Approve and send$")


def approve_every_day(request_url, minutes, days, period=None):
    """As the Guardian: allow [minutes] a day for [days] days, or in each [period] (a chip, e.g. r"^1 hour$").
    Returns the shared text and the approval link."""
    open_link(request_url)
    tap_text(r"^Repeating$", timeout=20)
    # On phones that take periods, the Every row comes first, with its own Custom chip.
    every = 1 if matching(r"^Every$") else 0
    if period:
        # "30 min", "1 hour" and "2 hours" are minutes chips too.
        tap_text(period, index=0)
    # Then a Custom chip for minutes and one for days. Tap both before typing, while both are
    # on screen; neither focuses its field, so tap each field (by its label) to type in it.
    tap_text(r"^Custom$", index=every)
    tap_text(r"^Custom$", index=every + 1)
    tap_text(r"^Minutes$")
    type_text(str(minutes))
    tap_text(r"^Days$")
    type_text(str(days))
    guardian_approve(PIN)
    text = shared_text(APPROVAL_TIMEOUT)
    approval = re.search(r"krypt://approve\?[A-Za-z0-9._~*%+=&-]+", text)
    expect(approval, f"no approval link in {text!r}")
    dismiss_share_sheet()
    return text, approval.group(0)


def approve_once(request_url, minutes):
    """As the Guardian: allow the app once, for [minutes] typed into Custom. Returns the approval link."""
    open_link(request_url)
    wait_guardian_form()
    tap_text(r"^Custom$")  # Once has no Every row, so this is For how long's Custom
    tap_text(r"^Minutes$")
    type_text(str(minutes))
    expect(wait_text(rf", {minutes} minutes?, once\.$", 10), f"{minutes} minutes once is not the choice")
    guardian_approve(PIN)
    text = shared_text(APPROVAL_TIMEOUT)
    approval = re.search(r"krypt://approve\?[A-Za-z0-9._~*%+=&-]+", text)
    expect(approval, f"no approval link in {text!r}")
    dismiss_share_sheet()
    return approval.group(0)


# ------------------------------------------------------------------ runner

class Check(Exception):
    pass


def expect(cond, message):
    if not cond:
        raise Check(message)


RESULTS = []


def save_evidence(name):
    """Keep what the device showed when a case failed: a screenshot, the UI dump and the focused window."""
    folder = os.path.join(EVIDENCE, re.sub(r"[^\w.-]+", "_", name)[:60])
    os.makedirs(folder, exist_ok=True)
    png = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    open(os.path.join(folder, "screen.png"), "wb").write(png)
    dump_ui()
    open(os.path.join(folder, "ui.xml"), "w", encoding="utf-8").write(adb("exec-out", "cat", "/sdcard/krypt_e2e.xml"))
    windows = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|isKeyguardShowing'")
    activities = sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity'")
    open(os.path.join(folder, "focus.txt"), "w", encoding="utf-8").write(windows + activities)
    return folder


def case(name):
    def wrap(fn):
        def run():
            started = time.time()
            try:
                fn()
                RESULTS.append((name, True, ""))
                print(f"PASS  {name}  ({time.time() - started:.0f}s)", flush=True)
            except Exception as e:  # noqa: BLE001 - report every failure and carry on
                RESULTS.append((name, False, str(e)))
                try:
                    where = save_evidence(name)
                except Exception:  # noqa: BLE001 - evidence is best effort
                    where = "not saved"
                print(f"FAIL  {name}: {e}  [evidence: {where}]", flush=True)
        run.case_name = name
        return run
    return wrap


STATE = {}


# ------------------------------------------------------------------ cases

@case("01 fresh install and permissions")
def install_fresh():
    for apk in (APP_APK, TARGET_APK):
        expect(os.path.exists(apk), f"missing {apk}; run ./gradlew :app:assembleDebug :e2e-target:assembleDebug")
    sh("svc power stayon true")
    sh("settings put system screen_off_timeout 1800000")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    # Chrome opens straight to a tab. Its first-run screens are a second activity that
    # arrives late, and when a screen dump has Krypt's service unbound just then, nothing
    # sees it arrive (see the README).
    sh(f"am set-debug-app --persistent {CHROME}")
    sh("echo '_ --disable-fre --no-default-browser-check --no-first-run' > /data/local/tmp/chrome-command-line")
    sh(f"pm grant {CHROME} android.permission.POST_NOTIFICATIONS")
    sh(f"am force-stop {CHROME}")
    sh("cmd time_detector set_auto_detection_enabled true")  # every-day time needs it
    adb("uninstall", TARGET)
    adb("uninstall", KRYPT)
    adb("logcat", "-c")
    expect("Success" in adb("install", "-r", "-g", APP_APK), "Krypt did not install")
    sh(f"appops set {KRYPT} SYSTEM_ALERT_WINDOW allow")
    # Not on the battery whitelist: "Keep running in the background" is optional (Amendment 5).
    expect(enable_krypt_service(), "Krypt's accessibility service could not be turned on")
    connected = wait_for(lambda: "connected" in adb("logcat", "-d", "-s", "KryptA11y:*"), timeout=30)
    expect(connected, "Krypt's accessibility service did not connect")


@case("02 new install is auto-locked and announced (before setup)")
def auto_lock_new_install():
    adb("logcat", "-c")
    expect("Success" in adb("install", "-r", TARGET_APK), "target app did not install")
    rows = wait_for(lambda: db(f"select lockState, lockSource from locked_apps where packageName='{TARGET}'"), 20)
    expect(rows == [("LOCKED", "DEFAULT_DENY")], f"locked_apps row: {rows}")
    notif = wait_for(lambda: "New app protected" in krypt_notification_text(), 10)
    expect(notif, "no 'New app protected' notification")


@case("03 locked app is blocked before setup")
def blocked_before_setup():
    launch(TARGET)
    expect(re.search(re.escape(LOCK_SCREEN), wait_top(re.escape(LOCK_SCREEN))), f"top is {top_activity()}")
    expect(wait_text(r"^Locked\.$"), "lock screen text missing")


@case("04 Request access before a PIN exists explains it (negative)")
def request_access_not_configured():
    tap_text(r"^Request access$")
    expect(wait_text(r"isn't set up yet", 15), "no 'not set up' message")
    expect("intentresolver" not in top_activity() and "ResolverActivity" not in top_activity(),
           "a share sheet opened without a PIN")


@case("05 Back to home leaves for the launcher")
def back_to_home_from_lock():
    tap_text(r"^Back to home$")
    launcher = launcher_package()
    expect(re.search(re.escape(launcher), wait_top(re.escape(launcher))), f"top is {top_activity()}")


@case("06 onboarding with the mandatory permissions granted")
def onboarding():
    launch(KRYPT)
    # First run asks who the phone is for; protecting it leads to the permission checklist.
    if wait_text(r"^I'm protecting this phone$", 30):
        tap_text(r"^I'm protecting this phone$")
    # Accessibility and the overlay; the battery exemption is optional and not granted here.
    expect(wait_text(r"2 of 2 required", 30), "mandatory progress is not 2 of 2")
    tap_text(r"^Start protecting$")
    expect(wait_text(r"^Give this phone to your Guardian\.$", 20), "PIN setup screen did not appear")


@case("07 PIN setup: mismatched PINs cannot be saved (negative)")
def pin_setup_mismatch():
    tap_text(r"^Continue$")  # the phone is with the Guardian now
    expect(wait_text(r"^Choose a PIN$", 10), "the PIN step did not open")
    enter_pin(PIN)
    tap_text(r"^Continue$")
    expect(wait_text(r"^Once more$", 10), "the confirm step did not open")
    enter_pin("1357")
    tap_text(r"^Save PIN$")
    expect(wait_text(r"Those PINs don't match", 10), "no 'PINs don't match' message")
    expect(not matching(r"Securing your PIN|^Guardian only$|^KRYPT ADMIN$"), "mismatched PINs were saved")
    expect(matching(r"^Choose a PIN$"), "not back on the PIN setup's first step")


@case("08 PIN setup saves the Guardian PIN")
def pin_setup_ok():
    # Case 07 left the setup on its first step again, with both PINs cleared.
    expect(wait_text(r"^Choose a PIN$", 10), "not on the PIN setup's first step")
    enter_pin(PIN)
    tap_text(r"^Continue$")
    expect(wait_text(r"^Once more$", 10), "the confirm step did not open")
    enter_pin(PIN)
    tap_text(r"^Save PIN$")
    expect(wait_text(r"^Guardian only$", 180), "app-entry screen did not appear after saving")


@case("09 app entry: wrong PIN is refused (negative)")
def app_entry_wrong_pin():
    enter_pin("1111")
    tap_text(r"^Continue$")
    expect(wait_text(r"That PIN isn't right\. 2 tries left\.", 120), "no wrong-PIN message")


@case("10 app entry: right PIN opens Home with the auto-locked app")
def app_entry_ok():
    enter_pin(PIN)
    tap_text(r"^Continue$")
    expect(wait_text(r"^Search apps$", 120), "Home did not open")
    expect(wait_text(r"Krypt E2E Target", 10), "target app missing from Home")
    expect(wait_text(LOCKED_STATUS, 5), "target app not shown as Locked")


@case("11 Home toggle locks Chrome")
def manual_lock_chrome():
    switch = wait_text(r"^Lock Chrome$", 10)  # Chrome's lock switch, by its label
    if not switch:  # below the fold: bring it up with the search
        search_home("Chrome")
        switch = wait_text(r"^Lock Chrome$", 10)
    expect(switch, "no lock switch for Chrome")
    sh(f"input tap {switch[0]['x']} {switch[0]['y']}")
    rows = wait_for(lambda: db(f"select lockState, lockSource from locked_apps where packageName='{CHROME}'"), 10)
    expect(rows == [("LOCKED", "MANUAL")], f"Chrome row: {rows}")


@case("12 leaving Krypt locks the Home Screen again")
def app_entry_relocks():
    home()
    launch(KRYPT)
    expect(wait_text(r"^Guardian only$", 20), "Krypt reopened without asking for the PIN")
    expect(not matching(r"^Search apps$"), "Home Screen visible without the PIN")
    home()


@case("13 launching a locked app shows the lock screen, never the app")
def blocked_chrome():
    adb("logcat", "-c")
    launch(CHROME)
    expect(re.search(re.escape(LOCK_SCREEN), wait_top(re.escape(LOCK_SCREEN))), f"top is {top_activity()}")
    expect(wait_text(r"^Chrome$"), "lock screen does not name Chrome")
    expect(CHROME not in top_activity(), "Chrome is in front")


@case("14 Back on the lock screen goes home, not into the app")
def back_goes_home():
    sh("input keyevent KEYCODE_BACK")
    launcher = launcher_package()
    top = wait_top(re.escape(launcher))
    expect(launcher in top, f"top is {top}")


@case("15 reopening a locked app from Recents is blocked")
def recents_blocked():
    launch(TARGET)
    wait_top(re.escape(LOCK_SCREEN))
    home()
    sh("input keyevent KEYCODE_APP_SWITCH")
    time.sleep(2.5)
    # Find the target's own card. Android keeps the latest task in Recents even when it is
    # excluded from them, so the lock screen's card can come first; older cards are to the left.
    card = wait_text(r"^Krypt E2E Target$", 10)
    for _ in range(3):
        if card:
            break
        sh("input swipe 250 1100 850 1100 300")
        time.sleep(1.5)
        card = matching(r"^Krypt E2E Target$")
    expect(card, "target app not in Recents")
    wait_for_krypt_service()
    sh(f"input tap {card[0]['x']} {card[0]['y']}")
    expect(re.search(re.escape(LOCK_SCREEN), wait_top(re.escape(LOCK_SCREEN))), f"top is {top_activity()}")


@case("16 Request access shares a request link and saves the pending request")
def request_access():
    expect(wait_text(r"Krypt E2E Target"), "not on the target's lock screen")
    tap_text(r"^Request access$")
    text = shared_text()
    m = re.search(r"krypt://request\?[A-Za-z0-9._~*%+=&-]+", text)
    expect(m and "Krypt access request for Krypt E2E Target" in text, f"share text: {text!r}")
    STATE["request"] = m.group(0)
    req = re.search(r"req=([0-9a-f-]+)", STATE["request"]).group(1)
    rows = db(f"select targetPackage, consumed from outstanding_requests where requestId='{req}'")
    expect(rows == [(TARGET, 0)], f"pending request row: {rows}")
    copy_from_share_sheet()  # leaves a *request* link on the clipboard for the next case
    dismiss_share_sheet()


@case("16b pasting a request link as a reply is refused (negative)")
def paste_request_as_approval():
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), f"not back on the lock screen; top is {top_activity()}")
    expect(wait_text(r"Krypt E2E Target"), "lock screen is not for the target app")
    tap_text(r"^Paste reply$")
    expect(wait_text(r"No reply on the clipboard", 10), "no 'no reply' message")
    expect(LOCK_SCREEN in top_activity(), "left the lock screen")


@case("16c Krypt dying under the lock screen leaves the home screen showing, not the app (negative)")
def process_death_under_lock_screen():
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), f"not on the lock screen; top is {top_activity()}")
    pid = sh(f"pidof {KRYPT}").strip()
    expect(pid, "Krypt is not running")
    sh(f"run-as {KRYPT} kill -9 {pid}")  # as a crash, an update or a low-memory kill would
    time.sleep(3)
    top = top_activity()
    expect(TARGET not in top, f"the locked app was left in front: {top}")
    launcher = launcher_package()
    expect(launcher in wait_top(re.escape(launcher)), f"top is {top_activity()}")
    restarted = wait_for(lambda: sh(f"pidof {KRYPT}").strip() not in ("", pid), timeout=30)
    expect(restarted, "Krypt did not restart")


@case("17 Guardian: wrong PIN is refused (negative)")
def guardian_wrong_pin():
    open_link(STATE["request"])
    wait_guardian_form()
    guardian_approve("0000")
    expect(wait_text(r"That PIN isn't right\. 2 tries left\.", 120), "no wrong-PIN message")


@case("18 Guardian: an expired request is refused (negative)")
def guardian_expired():
    iat = int(re.search(r"iat=(\d+)", STATE["request"]).group(1))
    open_link(STATE["request"].replace(f"iat={iat}", f"iat={iat - 3600}"))
    expect(wait_text(r"This request expired", 20), "expired request was not refused")


@case("19 Guardian: a garbled request is refused (negative)")
def guardian_garbled():
    open_link("krypt://request?v=1&req=not-a-uuid")
    expect(wait_text(r"Couldn't read this request", 20), "garbled request was not refused")


@case("20 legacy krypt://pair links are no longer handled (negative)")
def legacy_pair_link():
    out = open_link("krypt://pair?v=1&sub=12345678-1234-4abc-8def-123456789abc")
    expect(re.search(r"unable to resolve|no activity|error", out, re.I), f"am start said: {out.strip()}")


@case("21 Guardian: right PIN shares an approval link")
def guardian_ok():
    open_link(STATE["request"])
    wait_guardian_form()
    guardian_approve(PIN)
    text = shared_text(APPROVAL_TIMEOUT)
    m = re.search(r"krypt://approve\?[A-Za-z0-9._~*%+=&-]+", text)
    expect(m and "Krypt reply for Krypt E2E Target" in text, f"share text: {text!r}")
    STATE["approval"] = m.group(0)
    dismiss_share_sheet()
    home()


@case("22 a tampered approval unlocks nothing (negative)")
def tampered_approval():
    url = STATE["approval"]
    data = re.search(r"data=([^&]+)", url).group(1)
    flipped = data[:10] + ("A" if data[10] != "A" else "B") + data[11:]
    open_link(url.replace(data, flipped))
    time.sleep(3)
    expect(db(f"select count(*) from unlock_grants where targetPackage='{TARGET}'") == [(0,)], "a grant was made")
    launch(TARGET)
    expect(re.search(re.escape(LOCK_SCREEN), wait_top(re.escape(LOCK_SCREEN))), f"top is {top_activity()}")
    home()


@case("23 tapping the approval link unlocks and opens the app")
def approval_opens_app():
    adb("logcat", "-c")
    open_link(STATE["approval"])
    STATE["granted_at"] = time.time()
    top = wait_top(re.escape(TARGET_ACTIVITY), 20)
    expect(TARGET_ACTIVITY in top, f"top is {top}")
    expect(wait_text(r"E2E target is open", 10), "target app not showing")
    req = re.search(r"req=([0-9a-f-]+)", STATE["approval"]).group(1)
    expect(db(f"select consumed from outstanding_requests where requestId='{req}'") == [(1,)], "request not consumed")
    grants = db(f"select (expiresAt - grantedAt) / 60000 from unlock_grants where targetPackage='{TARGET}'")
    expect(grants == [(15,)], f"grants: {grants}")
    expect("LockScreenActivity" not in sh("dumpsys activity activities | grep -i LockScreenActivity"),
           "the lock screen was left behind")


@case("24 during the grant the app opens freely")
def relaunch_during_grant():
    home()
    adb("logcat", "-c")
    launch(TARGET)
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), f"top is {top_activity()}")
    expect(not krypt_logcat(rf"blocked pkg={re.escape(TARGET)}"), "the granted app was blocked")
    expect(not pill_shown(), "a countdown shows with most of the 15 minutes left")


@case("25 replaying the approval link grants nothing more (negative)")
def replay_approval():
    open_link(STATE["approval"])
    time.sleep(3)
    expect(db(f"select count(*) from unlock_grants where targetPackage='{TARGET}'") == [(1,)], "replay made a grant")


@case("26 the grant survives Krypt's process restarting")
def grant_survives_restart():
    home()
    restart_krypt()
    adb("logcat", "-c")
    launch(TARGET)
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), f"top is {top_activity()}")
    home()


@case("26b Home shows the Guardian's unlock with its time left (the reported bug)")
def home_shows_unlock():
    open_krypt_home()
    search_home("E2E")
    expect(wait_text(r"^Krypt E2E Target$", 10), "target app missing from Home")
    status = wait_text(r"^Open · \d+ min left$", 10)
    expect(status, "the unlocked app is not shown as open")
    minutes = int(re.search(r"(\d+) min", status[0]["text"]).group(1))
    expect(1 <= minutes <= 15, f"time left shown as {minutes} min")
    switches = home_switches()
    expect(len(switches) == 1 and switches[0]["checked"], f"the lock switch should stay on: {switches}")


@case("26c Relock now ends the unlock: the app is blocked again (negative)")
def relock_now_ends_unlock():
    tap_text(r"^Krypt E2E Target$")  # a row open now has no buttons; it opens a sheet
    tap_text(r"^Relock now$")
    expect(wait_text(LOCKED_STATUS, 10), "the row did not change to Locked")
    expect(not matching(r"^Open · "), "the row still shows the unlock")
    home()
    launch(TARGET)
    top = wait_top(re.escape(LOCK_SCREEN))
    expect(LOCK_SCREEN in top, f"the app still opened after Relock now; top is {top}")
    home()


@case("27 copy-and-paste path, with the Guardian allowing 30 minutes: Chrome unlocked for 30 min")
def paste_approval_path():
    launch(CHROME)
    wait_top(re.escape(LOCK_SCREEN))
    tap_text(r"^Request access$")
    request = re.search(r"krypt://request\?[A-Za-z0-9._~*%+=&-]+", shared_text())
    expect(request, "no request link for Chrome")
    dismiss_share_sheet()
    open_link(request.group(0))
    tap_text(r"^30 min$", timeout=20)
    expect(wait_text(r"^Chrome, 30 minutes, once\.$", 10), "30 minutes not selected")
    guardian_approve(PIN)
    text = shared_text(APPROVAL_TIMEOUT)
    expect("Krypt reply for Chrome: 30 minutes, once." in text, f"share text: {text!r}")
    copy_from_share_sheet()
    dismiss_share_sheet()
    launch(CHROME)
    wait_top(re.escape(LOCK_SCREEN))
    tap_text(r"^Paste reply$")
    top = wait_top(re.escape(CHROME) + r"/", 20)
    expect(CHROME in top, f"top is {top}")
    grants = db(f"select (expiresAt - grantedAt) / 60000 from unlock_grants where targetPackage='{CHROME}'")
    expect(grants == [(30,)], f"Chrome's grant: {grants}")
    home()


@case("28 catch-up: an app installed while Krypt was off is locked when it comes back")
def catch_up_after_protection_off():
    # Every uiautomator dump so far unbound Krypt's service for a moment; none of them is a switch-off.
    expect(not protection_off_alert_showing(), "'Krypt is off' was shown though Krypt was never switched off")
    sh("settings put secure enabled_accessibility_services ''")
    # Switching Krypt off is reported at once, not at the next health check (Amendment 5).
    expect(wait_for(protection_off_alert_showing, 30), "no 'Krypt is off' alert when Krypt was switched off")
    time.sleep(3)
    adb("uninstall", TARGET)
    expect("Success" in adb("install", "-r", TARGET_APK), "target app did not reinstall")
    time.sleep(2)
    adb("logcat", "-c")
    sh(f"settings put secure enabled_accessibility_services {A11Y_SERVICE}")
    rows = wait_for(lambda: db(f"select lockState from locked_apps where packageName='{TARGET}'")
                    == [("LOCKED",)] and krypt_logcat(r"locked new install pkg=com\.krypt\.e2e\.target"), 30)
    expect(rows, "reinstalled app was not re-locked on catch-up")
    expect(wait_for(lambda: not protection_off_alert_showing(), 30),
           "the 'Krypt is off' alert stayed after Krypt came back")
    launch(TARGET)
    expect(re.search(re.escape(LOCK_SCREEN), wait_top(re.escape(LOCK_SCREEN))), f"top is {top_activity()}")
    home()


@case("28b every day: the Guardian allows 1 minute a day for 2 days, and the app opens")
def daily_approval_opens_app():
    grants_before = db(f"select count(*) from unlock_grants where targetPackage='{TARGET}'")[0][0]
    request = request_access_for(TARGET)
    text, approval = approve_every_day(request, minutes=1, days=2)
    expect("1 minute a day for 2 days" in text, f"share text: {text!r}")
    open_link(approval)
    top = wait_top(re.escape(TARGET_ACTIVITY), 20)
    expect(TARGET_ACTIVITY in top, f"the approved app did not open; top is {top}")
    home()  # keep today's use short for 28d
    rule = db(f"select minutesPerDay, lastEpochDay - firstEpochDay from daily_allowances where packageName='{TARGET}'")
    expect(rule == [(1, 1)], f"daily rule: {rule}")
    grants_after = db(f"select count(*) from unlock_grants where targetPackage='{TARGET}'")[0][0]
    expect(grants_after == grants_before, "an every-day approval also made a one-time grant")
    expect("LockScreenActivity" not in sh("dumpsys activity activities | grep -i LockScreenActivity"),
           "the lock screen was left behind")


@case("28c automatic date & time off pauses daily time (negative)")
def daily_time_paused_without_auto_time():
    try:
        set_auto_time(False)
        launch(TARGET)
        expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), f"top is {top_activity()}")
        expect(wait_text(r"^Paused\.$", 10), "the lock screen doesn't say daily time is paused")
        home()
    finally:
        set_auto_time(True)
    launch(TARGET)
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), f"daily time did not come back; top is {top_activity()}")
    home()


@case("28d a minute of use counts down over the app, runs out and blocks it; usage survives a restart of Krypt")
def daily_time_runs_out():
    used_before = daily_used_ms(TARGET)
    expect(used_before < 20_000, f"{used_before} ms already used today")
    launch(TARGET)
    started = time.time()
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), f"top is {top_activity()}")
    # Less than a minute of today's time is left, so the countdown shows at once (Amendment 7).
    # No uiautomator from here on: a dump unbinds Krypt's service, and the countdown with it.
    expect(wait_for(pill_shown, 10), "no countdown over the app in its last minute")
    screenshot("28d_countdown")
    expect_taps_reach_the_app_through_the_pill()
    time.sleep(max(0.0, started + 36 - time.time()))  # counting, with the countdown up
    restart_krypt()
    # Krypt saves 30 s after its last save, which was when 28c's use stopped, so a few seconds less than
    # 30 s of this use is saved by now. A second or two would mean the countdown's window stopped the count.
    used = daily_used_ms(TARGET)
    expect(used >= used_before + 5_000, f"nothing saved during use: {used} ms (was {used_before})")
    home()
    launch(TARGET)  # a window change, so counting resumes
    expect(wait_for(pill_shown, 10), "no countdown when the app came back after Krypt restarted")
    top = wait_top(re.escape(LOCK_SCREEN), timeout=(60_000 - used) // 1000 + 20)
    expect(LOCK_SCREEN in top, f"not blocked when today's minute ran out; top is {top}")
    expect(wait_for(lambda: not pill_shown(), 3), "the countdown stayed up over the lock screen")
    # Just after Krypt's restart, a screen dump can take most of 10 s on its own.
    expect(wait_text(r"^Done for today\.$", 20), "the lock screen doesn't say today's time is up")
    expect(wait_text(r"^Request access$", 5), "Request access is not offered for extra time")
    home()
    launch(TARGET)
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), "the used-up app opened again")
    home()


@case("29 the health check is scheduled, and nothing of Krypt runs in the foreground")
def health_check_scheduled():
    # WorkManager skips a periodic job that is forced early, so the alert
    # itself is covered by the instrumented AccessibilityHealthWorkerTest.
    jobs = sh("dumpsys jobscheduler")
    expect(re.search(r"com\.krypt\.app/androidx\.work\.impl\.background\.systemjob\.SystemJobService", jobs),
           "health-check job not scheduled")
    # Amendment 5: no foreground service, and so no notification that never goes away.
    expect("isForeground=true" not in sh(f"dumpsys activity services {KRYPT}"),
           "a Krypt service runs in the foreground")


@case("29b a reboot ends every grant (negative)")
def reboot_ends_grants():
    # Chrome's grant from case 27 still has minutes left.
    launch(CHROME)
    top = wait_top(re.escape(CHROME) + r"/")
    expect(CHROME in top, f"Chrome was not open before the reboot; top is {top}")
    home()
    reboot_device()
    launch(CHROME)
    top = wait_top(re.escape(LOCK_SCREEN))
    expect(LOCK_SCREEN in top, f"Chrome opened on a grant from before the reboot; top is {top}")
    home()
    launch(TARGET)
    top = wait_top(re.escape(LOCK_SCREEN))
    expect(LOCK_SCREEN in top, f"the used-up app opened after the reboot; top is {top}")
    # The first screen dumps after a reboot are slow, and the lock screen may still be Chrome's.
    if not wait_text(rf"^{re.escape(TARGET)}$", 60):
        expect(False, f"the lock screen is not for {TARGET}; it shows {screen_texts()}")
    if not wait_text(r"^Done for today\.$", 20):
        expect(False, f"after the reboot, today's time is no longer used up; the lock screen shows "
                      f"{screen_texts()}, and {daily_used_ms(TARGET)} ms is saved for today")
    home()


@case("30 an app still open when its grant ends is blocked (15-min wait)")
def grant_expiry_in_app():
    # Fresh grant for the target, then keep it in front until it runs out.
    daily_before = daily_used_ms(TARGET)
    launch(TARGET)
    wait_top(re.escape(LOCK_SCREEN))
    tap_text(r"^Request access$")
    request = re.search(r"krypt://request\?[A-Za-z0-9._~*%+=&-]+", shared_text())
    expect(request, "no request link")
    dismiss_share_sheet()
    open_link(request.group(0))
    wait_guardian_form()
    guardian_approve(PIN)
    approval = re.search(r"krypt://approve\?[A-Za-z0-9._~*%+=&-]+", shared_text(APPROVAL_TIMEOUT))
    expect(approval, "no approval link")
    dismiss_share_sheet()
    wait_for_krypt_service()  # the Guardian's screen dumps unbound it; it must see the app open
    open_link(approval.group(0))
    granted = time.time()
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), "target did not open")
    time.sleep(max(0, granted + 13 * 60 + 5 - time.time()))  # into the last two minutes
    expect(TARGET_ACTIVITY in top_activity(), "target left the foreground before expiry")
    expect(wait_for(pill_shown, 10), "no countdown in the unlock's last two minutes")
    top = wait_top(re.escape(LOCK_SCREEN), timeout=150)
    expect(LOCK_SCREEN in top, f"not blocked when the grant ended; top is {top}")
    late = time.time() - granted - 15 * 60
    expect(late < 15, f"blocked {late:.0f} s after the grant ended")
    expect(wait_for(lambda: not pill_shown(), 3), "the countdown stayed up over the lock screen")
    home()
    launch(TARGET)
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), "relaunch after expiry was not blocked")
    home()
    expect(daily_used_ms(TARGET) == daily_before, "time under the one-time unlock was counted as daily time")


@case("30e an unlock still ends on time when the screen was off during it, with the countdown at the end")
def unlock_ends_on_time_after_screen_off():
    # The reported bug: the phone slept during an unlock, and the app stayed open after it ended,
    # until it was opened again. A coroutine timer stops while the phone sleeps (Amendment 7). An
    # emulator doesn't suspend, so there this covers the screen going off and on during the unlock,
    # and TimeLeftWatcherTest covers the time lost in sleep; the note says which this device did.
    request = request_access_for(TARGET)
    approval = approve_once(request, minutes=3)
    home()
    adb("logcat", "-c")
    wait_for_krypt_service()  # the Guardian's screen dumps unbound it; it must see the app open
    open_link(approval)
    granted = time.time()
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY), 20), "target did not open")
    expect(not pill_shown(), "a countdown with three minutes left")
    time.sleep(10)
    sh("input keyevent KEYCODE_SLEEP")  # the screen goes off; the phone may sleep
    off_at = time.time()
    time.sleep(75)
    off_s = time.time() - off_at
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    power = sh("dumpsys power")
    went, came = re.search(r"mLastSleepTime=(\d+)", power), re.search(r"mLastWakeTime=(\d+)", power)
    if went and came:  # both on the uptime clock, which stops while the phone is suspended
        suspended = off_s - (int(came.group(1)) - int(went.group(1))) / 1000
        print(f"      note: suspended for about {max(0.0, suspended):.0f} s of the {off_s:.0f} s the screen was off",
              flush=True)
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY), 15),
           f"the app is not in front after the screen came back; top is {top_activity()}")
    expect(wait_for(pill_shown, 10), "no countdown in the unlock's last two minutes")
    screenshot("30e_countdown")
    expect_taps_reach_the_app_through_the_pill()
    home()
    expect(wait_for(lambda: not pill_shown(), 5), "the countdown stayed up on the home screen")
    launch(TARGET)  # the unlock is still running
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), f"top is {top_activity()}")
    expect(wait_for(pill_shown, 10), "no countdown when the app came back")
    time.sleep(max(0.0, granted + 3 * 60 - 20 - time.time()))
    screenshot("30e_last_seconds")  # the hourglass in caution orange
    top = wait_top(re.escape(LOCK_SCREEN), timeout=150)
    expect(LOCK_SCREEN in top, f"not blocked when the unlock ended; top is {top}")
    late = time.time() - granted - 3 * 60
    expect(late < 10, f"blocked {late:.0f} s after the unlock ended")
    expect(wait_for(lambda: not pill_shown(), 3), "the countdown stayed up over the lock screen")
    expect(krypt_logcat(rf"blocked pkg={re.escape(TARGET)} \(time up\)"), "the block wasn't for the time being up")
    home()


@case("30f an app in picture-in-picture keeps its time and countdown, and its small window closes when the time ends")
def pip_keeps_its_time():
    # Amendment 8: an app in a small window is in use too. Krypt used to count only the app with focus.
    request = request_access_for(TARGET)
    approval = approve_once(request, minutes=3)
    home()
    adb("logcat", "-c")
    wait_for_krypt_service()  # the Guardian's screen dumps unbound it; it must see the app open
    open_link(approval)
    granted = time.time()
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY), 20), "target did not open")
    sh(f"am broadcast -f 0x20 -n {TARGET}/.EnterPip")
    expect(wait_for(target_in_pip, 10), "the target did not go into picture-in-picture")
    # Android 11 and later give the launcher focus now; on Android 10 the small window keeps it.
    time.sleep(max(0.0, granted + 60 + 5 - time.time()))  # into the last two minutes
    expect(target_in_pip(), "the target left picture-in-picture")
    expect(wait_for(pill_shown, 10), "no countdown for the app in picture-in-picture")
    screenshot("30f_pip_countdown")
    top = wait_top(re.escape(LOCK_SCREEN), timeout=150)
    expect(LOCK_SCREEN in top, f"not blocked when the unlock ended; top is {top}")
    late = time.time() - granted - 3 * 60
    expect(late < 10, f"blocked {late:.0f} s after the unlock ended")
    expect(wait_for(lambda: not target_in_pip(), 5), "the picture-in-picture window stayed open")
    expect(krypt_logcat(rf"closing a window of pkg={re.escape(TARGET)}: closed"), "Krypt did not close the small window")
    home()


@case("30b setting the date back and restarting Krypt doesn't revive an ended grant (negative)")
def date_rollback_after_expiry():
    now_ms = device_time_ms()
    ended = db(f"select count(*) from unlock_grants where targetPackage='{TARGET}' and expiresAt <= {now_ms}")
    expect(ended[0][0] >= 1, "no ended grant for the target (case 30 leaves one)")
    try:
        set_device_time(now_ms - 30 * 60_000)  # by the wall clock, case 30's grant is live again
        restart_krypt()
        launch(TARGET)
        top = wait_top(re.escape(LOCK_SCREEN))
        expect(LOCK_SCREEN in top, f"the ended grant came back; top is {top}")
        home()
    finally:
        restore_device_time()


@case("30c moving the date forward gives no fresh daily time (negative)")
def date_forward_gives_no_new_day():
    try:
        set_device_time(device_time_ms() + 24 * 60 * 60_000)  # also switches automatic time off
        launch(TARGET)
        expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), f"top is {top_activity()}")
        expect(wait_text(r"^Paused\.$", 10), "daily time not paused with automatic time off")
        home()
        set_auto_time(True)  # back on, the date still a day ahead
        launch(TARGET)
        top = wait_top(re.escape(LOCK_SCREEN))
        expect(LOCK_SCREEN in top, f"a changed date gave a fresh day of time; top is {top}")
        expect(wait_text(r"^Done for today\.$", 10), "the lock screen doesn't say today's time is up")
        home()
    finally:
        restore_device_time()


@case("30d a far date jump and a restart of Krypt don't erase today's used-up time (negative)")
def date_jump_keeps_used_up_day():
    # A rule that outlasts the jump, so only today's saved use could be lost. Today's use carries over.
    request = request_access_for(TARGET)
    _, approval = approve_every_day(request, minutes=1, days=60)
    open_link(approval)
    rule_sql = f"select minutesPerDay, lastEpochDay - firstEpochDay from daily_allowances where packageName='{TARGET}'"
    expect(wait_for(lambda: db(rule_sql) == [(1, 59)], timeout=20), f"daily rule: {db(rule_sql)}")
    time.sleep(2)
    expect(TARGET_ACTIVITY not in top_activity(), "the approval opened the app with today's time used up")
    used = daily_used_ms(TARGET)
    expect(used >= 60_000, f"only {used} ms used today")
    try:
        set_device_time(device_time_ms() + 40 * 24 * 60 * 60_000)  # also switches automatic time off
        restart_krypt()  # Krypt tidies old every-day data as it starts
    finally:
        restore_device_time()
    restart_krypt()  # and reads back what is left
    saved = daily_used_ms(TARGET)
    expect(saved >= used, f"today's saved use was erased: {saved} ms saved, {used} ms before")
    launch(TARGET)
    top = wait_top(re.escape(LOCK_SCREEN))
    expect(LOCK_SCREEN in top, f"a date jump gave a fresh day of time; top is {top}")
    expect(wait_text(r"^Done for today\.$", 10), "the lock screen doesn't say today's time is up")
    home()


@case("30g another app's window showing over a timed app keeps its time, countdown and end (the reported bug)")
def other_window_keeps_the_app_in_front():
    # The reported bug (Amendment 8): a home gesture started and cancelled showed the launcher's window
    # behind Instagram, Instagram kept focus, and Krypt took the launcher for the app in front: the
    # countdown went away and the time stopped. Here the target shows a small window over Chrome without
    # taking focus, as the launcher did. Its window may show only if it has time of its own.
    approval = approve_once(request_access_for(TARGET), minutes=15)
    home()
    wait_for_krypt_service()
    open_link(approval)
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY), 20), "target did not open")
    home()
    approval = approve_once(request_access_for(CHROME), minutes=2)
    home()
    adb("logcat", "-c")
    wait_for_krypt_service()
    open_link(approval)
    granted = time.time()
    expect(CHROME in wait_top(re.escape(CHROME) + r"/", 20), f"Chrome did not open; top is {top_activity()}")
    expect(wait_for(pill_shown, 10), "no countdown over Chrome with 2 minutes left")
    sh(f"appops set {TARGET} SYSTEM_ALERT_WINDOW allow")
    adb("logcat", "-c")
    sh(f"am broadcast -f 0x20 -n {TARGET}/.ShowOverlay")
    expect(wait_for(lambda: window_frame("KryptE2EOverlay"), 10, step=0.3), "the other app's window did not show")
    # Chrome sends window events of its own now and then, which used to set it right again after a
    # moment (Instagram didn't), so look closely: the countdown must never go, even briefly.
    samples = [pill_shown() for _ in range(8)]
    expect(all(samples), f"the countdown went away when another app's window showed: {samples}")
    expect(not krypt_logcat(r"countdown shown"), "the countdown was taken down and shown again meanwhile")
    expect(CHROME in top_activity(), f"Chrome left the front; top is {top_activity()}")
    top = wait_top(re.escape(LOCK_SCREEN), timeout=150)
    expect(LOCK_SCREEN in top, f"Chrome was not blocked when its unlock ended; top is {top}")
    late = time.time() - granted - 2 * 60
    expect(late < 10, f"blocked {late:.0f} s after the unlock ended")
    expect(krypt_logcat(r"blocked pkg=com\.android\.chrome \(time up\)"), "the block wasn't for Chrome's time being up")
    home()


@case("31 Device Admin blocks uninstalling Krypt (leaves Krypt installed)")
def uninstall_blocked():
    out = sh(f"dpm set-active-admin {KRYPT}/.service.KryptDeviceAdminReceiver")
    expect("Success" in out, f"dpm said: {out.strip()}")
    result = adb("uninstall", KRYPT)
    expect("DELETE_FAILED_DEVICE_POLICY_MANAGER" in result, f"uninstall said: {result.strip()}")


@case("31b every hour: 1 minute an hour runs out, and the lock screen says when it comes back")
def hourly_time_runs_out():
    # Chrome has no rule yet, so none of this hour is used. Two days, so the next hour is covered even near midnight.
    request = request_access_for(CHROME)
    text, approval = approve_every_day(request, minutes=1, days=2, period=r"^1 hour$")
    expect("1 minute an hour for 2 days" in text, f"share text: {text!r}")
    wait_for_krypt_service()  # the Guardian's screen dumps unbound it; it must see Chrome open
    open_link(approval)
    top = wait_top(re.escape(CHROME) + r"/", 20)
    expect(CHROME in top, f"Chrome did not open; top is {top}")
    rule = db(f"select minutesPerDay, periodMinutes from daily_allowances where packageName='{CHROME}'")
    expect(rule == [(1, 60)], f"rule: {rule}")
    # A minute of use, two if it starts just before the hour turns: the countdown shows at once.
    # No uiautomator dumps meanwhile: a dump unbinds Krypt's service.
    expect(wait_for(pill_shown, 10), "no countdown over Chrome with the hour's minute running out")
    screenshot("31b_countdown")
    top = wait_top(re.escape(LOCK_SCREEN), timeout=150)
    expect(LOCK_SCREEN in top, f"not blocked when the hour's minute ran out; top is {top}")
    expect(wait_for(lambda: not pill_shown(), 3), "the countdown stayed up over the lock screen")
    expect(wait_text(r"^Done for now\.$", 10), "the lock screen doesn't say the time is up for now")
    expect(wait_text(r"More time at \d", 5), "the lock screen doesn't say when the time comes back")
    expect(wait_text(r"^Request access$", 5), "Request access is not offered for extra time")
    home()


@case("32 For good: the Guardian unlocks Chrome for good, and it opens with no time limit")
def for_good_removes_the_lock():
    # Last, so that nothing after it needs Chrome locked.
    request = request_access_for(CHROME)
    open_link(request)
    wait_guardian_form()
    tap_text(r"^For good$")
    expect(wait_text(r"^Chrome, unlocked for good\.$", 10), "the summary doesn't say for good")
    expect(not matching(r"^For how long$"), "a duration is still offered for good")
    guardian_approve(PIN)
    text = shared_text(APPROVAL_TIMEOUT)
    approval = re.search(r"krypt://approve\?[A-Za-z0-9._~*%+=&-]+", text)
    expect(approval and "Krypt reply for Chrome: unlocked for good." in text, f"share text: {text!r}")
    dismiss_share_sheet()
    home()
    open_link(approval.group(0))
    top = wait_top(re.escape(CHROME) + r"/", 20)
    expect(CHROME in top, f"Chrome did not open; top is {top}")
    rows = db(f"select lockState from locked_apps where packageName='{CHROME}'")
    expect(rows == [("UNLOCKED",)], f"Chrome's lock: {rows}")
    home()
    launch(CHROME)  # no grant to run out: it just opens
    top = wait_top(re.escape(CHROME) + r"/", 20)
    expect(CHROME in top and LOCK_SCREEN not in top, f"Chrome was blocked again; top is {top}")
    home()


QUICK_SKIP = {"30", "30b", "30c", "30d", "31"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--quick", action="store_true", help="skip the 15-minute expiry case and Device Admin")
    args = parser.parse_args()
    all_cases = [install_fresh, auto_lock_new_install, blocked_before_setup, request_access_not_configured,
                 back_to_home_from_lock, onboarding, pin_setup_mismatch, pin_setup_ok, app_entry_wrong_pin,
                 app_entry_ok, manual_lock_chrome, app_entry_relocks, blocked_chrome, back_goes_home,
                 recents_blocked, request_access, paste_request_as_approval, process_death_under_lock_screen,
                 guardian_wrong_pin, guardian_expired, guardian_garbled,
                 legacy_pair_link, guardian_ok, tampered_approval, approval_opens_app, relaunch_during_grant,
                 replay_approval, grant_survives_restart, home_shows_unlock, relock_now_ends_unlock,
                 paste_approval_path, catch_up_after_protection_off, daily_approval_opens_app,
                 daily_time_paused_without_auto_time, daily_time_runs_out,
                 health_check_scheduled, reboot_ends_grants, grant_expiry_in_app,
                 unlock_ends_on_time_after_screen_off, pip_keeps_its_time, date_rollback_after_expiry,
                 date_forward_gives_no_new_day, date_jump_keeps_used_up_day, other_window_keeps_the_app_in_front,
                 uninstall_blocked,
                 hourly_time_runs_out, for_good_removes_the_lock]
    shutil.rmtree(EVIDENCE, ignore_errors=True)
    print(f"Krypt e2e on {SERIAL}: Android {sh('getprop ro.build.version.release').strip()}", flush=True)
    for run in all_cases:
        if args.quick and run.case_name.split()[0] in QUICK_SKIP:
            print(f"SKIP  {run.case_name}")
            continue
        run()
    failed = [r for r in RESULTS if not r[1]]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} passed")
    for name, _, detail in failed:
        print(f"  FAILED {name}: {detail}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
