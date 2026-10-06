"""Krypt end-to-end tests on a real device or emulator, driven over adb.

Covers what JVM and instrumented tests cannot: the accessibility service
blocking other apps, the new-install auto-lock, the share sheet, and the
full Subject -> Guardian -> Subject round trip across apps.

Prerequisites (see tests/e2e/README.md):
  ./gradlew :app:assembleDebug :e2e-target:assembleDebug
  one device/emulator on adb (Android 10+), ideally freshly wiped.

Usage:
  python tests/e2e/krypt_e2e.py            # full run, ~40 min (waits out a 15-min grant)
  python tests/e2e/krypt_e2e.py --quick    # skip the grant-expiry and uninstall cases

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


def type_pin(pin):
    sh(f"input text {pin}")
    sh("input keyevent KEYCODE_BACK")  # hide the keyboard
    time.sleep(0.5)


def open_krypt_home():
    """Open Krypt and get past its PIN gate to the Home list."""
    launch(KRYPT)
    if wait_text(r"^Search apps$", 3):
        return
    tap_text(r"^PIN$")
    type_pin(PIN)
    tap_text(r"^Unlock$")
    expect(wait_text(r"^Search apps$", 120), "Home did not open")


def search_home(text):
    tap_text(r"^Search apps$")
    type_pin(text)  # types it and hides the keyboard
    time.sleep(1)


def home_switches():
    """The lock switches on screen. Compose marks them checkable; their class is a plain View."""
    return [n for n in nodes() if n["checkable"]]


def top_activity():
    out = sh("dumpsys activity activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ u0 (\S+)", out)
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
    restarted = wait_for(lambda: sh(f"pidof {KRYPT}").strip() not in ("", pid), timeout=30)
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


def scroll_down():
    sh("input swipe 540 1800 540 600 400")
    time.sleep(1)


def daily_used_ms(pkg):
    rows = db(f"select usedMs from daily_usage where packageName='{pkg}' order by epochDay desc limit 1")
    return rows[0][0] if rows else 0


def set_auto_time(on):
    sh(f"cmd time_detector set_auto_detection_enabled {'true' if on else 'false'}")
    time.sleep(2)  # Krypt hears about it through a settings observer


def ask_guardian_for(pkg):
    """On the child's phone: open the locked app, tap Ask Guardian, return the request link."""
    launch(pkg)
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), f"{pkg} was not blocked; top is {top_activity()}")
    tap_text(r"^Ask Guardian$")
    request = re.search(r"krypt://request\?[A-Za-z0-9._~*%+=&-]+", shared_text())
    expect(request, "no request link")
    expect("caps=daily" in request.group(0), "the request doesn't say this phone takes every-day approvals")
    dismiss_share_sheet()
    return request.group(0)


def approve_every_day(request_url, minutes, days):
    """As the Guardian: allow [minutes] a day for [days] days. Returns the shared text and the approval link."""
    open_link(request_url)
    tap_text(r"^Every day$", timeout=20)
    tap_text(r"^Set minutes$")
    tap_text(r"^Minutes \(1–1440\)$")
    type_pin(str(minutes))
    tap_text(r"^Set days$")
    tap_text(r"^Days \(1–365\)$")
    type_pin(str(days))
    scroll_down()  # the PIN field is below the fold now
    tap_text(r"^Enter PIN$", timeout=20)
    type_pin(PIN)
    tap_text(r"Verify & send approval")
    text = shared_text(APPROVAL_TIMEOUT)
    approval = re.search(r"krypt://approve\?[A-Za-z0-9._~*%+=&-]+", text)
    expect(approval, f"no approval link in {text!r}")
    dismiss_share_sheet()
    return text, approval.group(0)


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
    sh(f"dumpsys deviceidle whitelist +{KRYPT}")
    expect(enable_krypt_service(), "Krypt's accessibility service could not be turned on")
    connected = wait_for(lambda: "connected" in adb("logcat", "-d", "-s", "KryptA11y:*"), timeout=30)
    expect(connected, "Krypt's accessibility service did not connect")


@case("02 new install is auto-locked and announced (before setup)")
def auto_lock_new_install():
    adb("logcat", "-c")
    expect("Success" in adb("install", "-r", TARGET_APK), "target app did not install")
    rows = wait_for(lambda: db(f"select lockState, lockSource from locked_apps where packageName='{TARGET}'"), 20)
    expect(rows == [("LOCKED", "DEFAULT_DENY")], f"locked_apps row: {rows}")
    notif = wait_for(lambda: "New App Protected" in krypt_notification_text(), 10)
    expect(notif, "no 'New App Protected' notification")


@case("03 locked app is blocked before setup")
def blocked_before_setup():
    launch(TARGET)
    expect(re.search(re.escape(LOCK_SCREEN), wait_top(re.escape(LOCK_SCREEN))), f"top is {top_activity()}")
    expect(wait_text(r"This app is locked"), "lock screen text missing")


@case("04 Ask Guardian before a PIN exists explains it (negative)")
def ask_guardian_not_configured():
    tap_text(r"^Ask Guardian$")
    expect(wait_text(r"isn't set up yet", 15), "no 'not set up' message")
    expect("intentresolver" not in top_activity() and "ResolverActivity" not in top_activity(),
           "a share sheet opened without a PIN")


@case("05 Go to home screen leaves for the launcher")
def go_home_from_lock():
    tap_text(r"^Go to home screen$")
    launcher = launcher_package()
    expect(re.search(re.escape(launcher), wait_top(re.escape(launcher))), f"top is {top_activity()}")


@case("06 onboarding with the mandatory permissions granted")
def onboarding():
    launch(KRYPT)
    expect(wait_text(r"Required permissions: 3 of 3", 30), "mandatory progress is not 3 of 3")
    for _ in range(4):
        tap_text(r"^Next$")
    tap_text(r"^Finish$")
    expect(wait_text(r"^Set Guardian PIN$", 20), "PIN setup screen did not appear")


@case("07 PIN setup: mismatched PINs cannot be saved (negative)")
def pin_setup_mismatch():
    tap_text(r"^PIN$")
    type_pin(PIN)
    tap_text(r"^Confirm PIN$")
    type_pin("1357")
    tap_text(r"^Save PIN$")
    time.sleep(3)
    expect(not matching(r"Securing PIN|^Krypt Admin$"), "mismatched PINs were saved")
    expect(matching(r"^Set Guardian PIN$"), "left the PIN setup screen")


@case("08 PIN setup saves the Guardian PIN")
def pin_setup_ok():
    # Clear the confirm field and type the matching PIN.
    confirm = [n for n in nodes() if n["class"].endswith("EditText")][1]
    sh(f"input tap {confirm['x']} {confirm['y']}")
    sh("input keyevent KEYCODE_MOVE_END")
    for _ in range(6):
        sh("input keyevent KEYCODE_DEL")
    type_pin(PIN)
    tap_text(r"^Save PIN$")
    expect(wait_text(r"^Krypt Admin$", 180), "app-entry screen did not appear after saving")


@case("09 app entry: wrong PIN is refused (negative)")
def app_entry_wrong_pin():
    tap_text(r"^PIN$")
    type_pin("1111")
    tap_text(r"^Unlock$")
    expect(wait_text(r"Wrong PIN\. 2 attempts left\.", 120), "no wrong-PIN message")


@case("10 app entry: right PIN opens Home with the auto-locked app")
def app_entry_ok():
    tap_text(r"^PIN$")
    type_pin(PIN)
    tap_text(r"^Unlock$")
    expect(wait_text(r"^Search apps$", 120), "Home did not open")
    expect(wait_text(r"Krypt E2E Target", 10), "target app missing from Home")
    expect(wait_text(r"^Locked$", 5), "target app not shown as Locked")


@case("11 Home toggle locks Chrome")
def manual_lock_chrome():
    chrome = wait_text(r"^Chrome$", 10)
    expect(chrome, "Chrome not listed")
    switches = [n for n in nodes() if n["clickable"] and n["bounds"][1] <= chrome[0]["y"] <= n["bounds"][3]
                and n["x"] > 800]
    expect(switches, "no switch on Chrome's row")
    sh(f"input tap {switches[0]['x']} {switches[0]['y']}")
    rows = wait_for(lambda: db(f"select lockState, lockSource from locked_apps where packageName='{CHROME}'"), 10)
    expect(rows == [("LOCKED", "MANUAL")], f"Chrome row: {rows}")


@case("12 leaving Krypt locks the Home Screen again")
def app_entry_relocks():
    home()
    launch(KRYPT)
    expect(wait_text(r"^Krypt Admin$", 20), "Krypt reopened without asking for the PIN")
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


@case("16 Ask Guardian shares a request link and saves the pending request")
def ask_guardian():
    expect(wait_text(r"Krypt E2E Target"), "not on the target's lock screen")
    tap_text(r"^Ask Guardian$")
    text = shared_text()
    m = re.search(r"krypt://request\?[A-Za-z0-9._~*%+=&-]+", text)
    expect(m and "Krypt unlock request for Krypt E2E Target" in text, f"share text: {text!r}")
    STATE["request"] = m.group(0)
    req = re.search(r"req=([0-9a-f-]+)", STATE["request"]).group(1)
    rows = db(f"select targetPackage, consumed from outstanding_requests where requestId='{req}'")
    expect(rows == [(TARGET, 0)], f"pending request row: {rows}")
    copy_from_share_sheet()  # leaves a *request* link on the clipboard for the next case
    dismiss_share_sheet()


@case("16b pasting a request link as an approval is refused (negative)")
def paste_request_as_approval():
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), f"not back on the lock screen; top is {top_activity()}")
    expect(wait_text(r"Krypt E2E Target"), "lock screen is not for the target app")
    tap_text(r"^Paste approval link$")
    expect(wait_text(r"No approval link on the clipboard", 10), "no 'no approval link' message")
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
    expect(wait_text(r"Approve unlocking this app", 20), "Guardian screen did not open")
    tap_text(r"^Enter PIN$")
    type_pin("0000")
    tap_text(r"Verify & send approval")
    expect(wait_text(r"Wrong PIN\. 2 attempts left\.", 120), "no wrong-PIN message")


@case("18 Guardian: an expired request is refused (negative)")
def guardian_expired():
    iat = int(re.search(r"iat=(\d+)", STATE["request"]).group(1))
    open_link(STATE["request"].replace(f"iat={iat}", f"iat={iat - 3600}"))
    expect(wait_text(r"has expired", 20), "expired request was not refused")


@case("19 Guardian: a garbled request is refused (negative)")
def guardian_garbled():
    open_link("krypt://request?v=1&req=not-a-uuid")
    expect(wait_text(r"Could not read this unlock request", 20), "garbled request was not refused")


@case("20 legacy krypt://pair links are no longer handled (negative)")
def legacy_pair_link():
    out = open_link("krypt://pair?v=1&sub=12345678-1234-4abc-8def-123456789abc")
    expect(re.search(r"unable to resolve|no activity|error", out, re.I), f"am start said: {out.strip()}")


@case("21 Guardian: right PIN shares an approval link")
def guardian_ok():
    open_link(STATE["request"])
    expect(wait_text(r"Approve unlocking this app", 20), "Guardian screen did not open")
    tap_text(r"^Enter PIN$")
    type_pin(PIN)
    tap_text(r"Verify & send approval")
    text = shared_text(APPROVAL_TIMEOUT)
    m = re.search(r"krypt://approve\?[A-Za-z0-9._~*%+=&-]+", text)
    expect(m and "Krypt approval for" in text, f"share text: {text!r}")
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
    status = wait_text(r"^Unlocked · \d+ min left$", 10)
    expect(status, "the unlocked app is not shown as unlocked")
    minutes = int(re.search(r"(\d+) min", status[0]["text"]).group(1))
    expect(1 <= minutes <= 15, f"time left shown as {minutes} min")
    switches = home_switches()
    expect(len(switches) == 1 and switches[0]["checked"], f"the lock switch should stay on: {switches}")


@case("26c Lock now ends the unlock: the app is blocked again (negative)")
def lock_now_ends_unlock():
    tap_text(r"^Lock now$")
    expect(wait_text(r"^Locked$", 10), "the row did not change to Locked")
    expect(not matching(r"^Unlocked · "), "the row still shows the unlock")
    home()
    launch(TARGET)
    top = wait_top(re.escape(LOCK_SCREEN))
    expect(LOCK_SCREEN in top, f"the app still opened after Lock now; top is {top}")
    home()


@case("27 copy-and-paste path, with the Guardian allowing 30 minutes: Chrome unlocked for 30 min")
def paste_approval_path():
    launch(CHROME)
    wait_top(re.escape(LOCK_SCREEN))
    tap_text(r"^Ask Guardian$")
    request = re.search(r"krypt://request\?[A-Za-z0-9._~*%+=&-]+", shared_text())
    expect(request, "no request link for Chrome")
    dismiss_share_sheet()
    open_link(request.group(0))
    tap_text(r"^30 min$", timeout=20)
    expect(wait_text(r"^Allows: 30 minutes, one time$", 10), "30 minutes not selected")
    tap_text(r"^Enter PIN$", timeout=20)
    type_pin(PIN)
    tap_text(r"Verify & send approval")
    text = shared_text(APPROVAL_TIMEOUT)
    expect("30 minutes, one time" in text, f"share text: {text!r}")
    copy_from_share_sheet()
    dismiss_share_sheet()
    launch(CHROME)
    wait_top(re.escape(LOCK_SCREEN))
    tap_text(r"^Paste approval link$")
    top = wait_top(re.escape(CHROME) + r"/", 20)
    expect(CHROME in top, f"top is {top}")
    grants = db(f"select (expiresAt - grantedAt) / 60000 from unlock_grants where targetPackage='{CHROME}'")
    expect(grants == [(30,)], f"Chrome's grant: {grants}")
    home()


@case("28 catch-up: an app installed while Krypt was off is locked when it comes back")
def catch_up_after_protection_off():
    sh("settings put secure enabled_accessibility_services ''")
    time.sleep(3)
    adb("uninstall", TARGET)
    expect("Success" in adb("install", "-r", TARGET_APK), "target app did not reinstall")
    time.sleep(2)
    adb("logcat", "-c")
    sh(f"settings put secure enabled_accessibility_services {A11Y_SERVICE}")
    rows = wait_for(lambda: db(f"select lockState from locked_apps where packageName='{TARGET}'")
                    == [("LOCKED",)] and krypt_logcat(r"locked new install pkg=com\.krypt\.e2e\.target"), 30)
    expect(rows, "reinstalled app was not re-locked on catch-up")
    launch(TARGET)
    expect(re.search(re.escape(LOCK_SCREEN), wait_top(re.escape(LOCK_SCREEN))), f"top is {top_activity()}")
    home()


@case("28b every day: the Guardian allows 1 minute a day for 2 days, and the app opens")
def daily_approval_opens_app():
    grants_before = db(f"select count(*) from unlock_grants where targetPackage='{TARGET}'")[0][0]
    request = ask_guardian_for(TARGET)
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
        expect(wait_text(r"^Daily time is paused$", 10), "the lock screen doesn't say daily time is paused")
        home()
    finally:
        set_auto_time(True)
    launch(TARGET)
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), f"daily time did not come back; top is {top_activity()}")
    home()


@case("28d a minute of use runs out and blocks the app; usage survives a restart of Krypt")
def daily_time_runs_out():
    used_before = daily_used_ms(TARGET)
    expect(used_before < 20_000, f"{used_before} ms already used today")
    launch(TARGET)
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), f"top is {top_activity()}")
    time.sleep(36)  # counting; no uiautomator here, a dump would unbind Krypt's service
    restart_krypt()
    # Krypt saves every 30 s (counted from its last save), so 36 s of use saves at least 6 s of it.
    used = daily_used_ms(TARGET)
    expect(used >= used_before + 5_000, f"nothing saved during use: {used} ms (was {used_before})")
    home()
    launch(TARGET)  # a window change, so counting resumes
    top = wait_top(re.escape(LOCK_SCREEN), timeout=(60_000 - used) // 1000 + 20)
    expect(LOCK_SCREEN in top, f"not blocked when today's minute ran out; top is {top}")
    expect(wait_text(r"^Time's up for today$", 10), "the lock screen doesn't say today's time is up")
    expect(wait_text(r"^Ask Guardian$", 5), "Ask Guardian is not offered for extra time")
    home()
    launch(TARGET)
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), "the used-up app opened again")
    home()


@case("29 the accessibility health check is scheduled")
def health_check_scheduled():
    # WorkManager skips a periodic job that is forced early, so the alert
    # itself is covered by the instrumented AccessibilityHealthWorkerTest.
    jobs = sh("dumpsys jobscheduler")
    expect(re.search(r"com\.krypt\.app/androidx\.work\.impl\.background\.systemjob\.SystemJobService", jobs),
           "health-check job not scheduled")


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
    if not wait_text(r"^Time's up for today$", 20):
        expect(False, f"after the reboot, today's time is no longer used up; the lock screen shows "
                      f"{screen_texts()}, and {daily_used_ms(TARGET)} ms is saved for today")
    home()


@case("30 an app still open when its grant ends is blocked (15-min wait)")
def grant_expiry_in_app():
    # Fresh grant for the target, then keep it in front until it runs out.
    daily_before = daily_used_ms(TARGET)
    launch(TARGET)
    wait_top(re.escape(LOCK_SCREEN))
    tap_text(r"^Ask Guardian$")
    request = re.search(r"krypt://request\?[A-Za-z0-9._~*%+=&-]+", shared_text())
    expect(request, "no request link")
    dismiss_share_sheet()
    open_link(request.group(0))
    tap_text(r"^Enter PIN$", timeout=20)
    type_pin(PIN)
    tap_text(r"Verify & send approval")
    approval = re.search(r"krypt://approve\?[A-Za-z0-9._~*%+=&-]+", shared_text(APPROVAL_TIMEOUT))
    expect(approval, "no approval link")
    dismiss_share_sheet()
    open_link(approval.group(0))
    granted = time.time()
    expect(TARGET_ACTIVITY in wait_top(re.escape(TARGET_ACTIVITY)), "target did not open")
    time.sleep(max(0, granted + 15 * 60 - time.time() - 20))
    expect(TARGET_ACTIVITY in top_activity(), "target left the foreground before expiry")
    top = wait_top(re.escape(LOCK_SCREEN), timeout=90)
    expect(LOCK_SCREEN in top, f"not blocked when the grant ended; top is {top}")
    home()
    launch(TARGET)
    expect(LOCK_SCREEN in wait_top(re.escape(LOCK_SCREEN)), "relaunch after expiry was not blocked")
    home()
    expect(daily_used_ms(TARGET) == daily_before, "time under the one-time unlock was counted as daily time")


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
        expect(wait_text(r"^Daily time is paused$", 10), "daily time not paused with automatic time off")
        home()
        set_auto_time(True)  # back on, the date still a day ahead
        launch(TARGET)
        top = wait_top(re.escape(LOCK_SCREEN))
        expect(LOCK_SCREEN in top, f"a changed date gave a fresh day of time; top is {top}")
        expect(wait_text(r"^Time's up for today$", 10), "the lock screen doesn't say today's time is up")
        home()
    finally:
        restore_device_time()


@case("30d a far date jump and a restart of Krypt don't erase today's used-up time (negative)")
def date_jump_keeps_used_up_day():
    # A rule that outlasts the jump, so only today's saved use could be lost. Today's use carries over.
    request = ask_guardian_for(TARGET)
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
    expect(wait_text(r"^Time's up for today$", 10), "the lock screen doesn't say today's time is up")
    home()


@case("31 Device Admin blocks uninstalling Krypt (leaves Krypt installed)")
def uninstall_blocked():
    out = sh(f"dpm set-active-admin {KRYPT}/.service.KryptDeviceAdminReceiver")
    expect("Success" in out, f"dpm said: {out.strip()}")
    result = adb("uninstall", KRYPT)
    expect("DELETE_FAILED_DEVICE_POLICY_MANAGER" in result, f"uninstall said: {result.strip()}")


QUICK_SKIP = {"30", "31"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--quick", action="store_true", help="skip the 15-minute expiry case and Device Admin")
    args = parser.parse_args()
    all_cases = [install_fresh, auto_lock_new_install, blocked_before_setup, ask_guardian_not_configured,
                 go_home_from_lock, onboarding, pin_setup_mismatch, pin_setup_ok, app_entry_wrong_pin,
                 app_entry_ok, manual_lock_chrome, app_entry_relocks, blocked_chrome, back_goes_home,
                 recents_blocked, ask_guardian, paste_request_as_approval, process_death_under_lock_screen,
                 guardian_wrong_pin, guardian_expired, guardian_garbled,
                 legacy_pair_link, guardian_ok, tampered_approval, approval_opens_app, relaunch_during_grant,
                 replay_approval, grant_survives_restart, home_shows_unlock, lock_now_ends_unlock,
                 paste_approval_path, catch_up_after_protection_off, daily_approval_opens_app,
                 daily_time_paused_without_auto_time, daily_time_runs_out,
                 health_check_scheduled, reboot_ends_grants, grant_expiry_in_app, date_rollback_after_expiry,
                 date_forward_gives_no_new_day, date_jump_keeps_used_up_day, uninstall_blocked]
    shutil.rmtree(EVIDENCE, ignore_errors=True)
    print(f"Krypt e2e on {SERIAL}: Android {sh('getprop ro.build.version.release').strip()}", flush=True)
    for run in all_cases:
        if args.quick and run.case_name[:2] in QUICK_SKIP:
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
