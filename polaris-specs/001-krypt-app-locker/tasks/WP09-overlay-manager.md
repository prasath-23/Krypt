---
work_package_id: WP09
lane: planned
dependencies: []
subtasks: [T040, T041, T042, T043, T044]
test_status: required
test_file: tests/e2e/WP09-wp09-overlay-manager.spec.js
---

# WP09 - OverlayManager + XML locker overlay layout

## Objective

Implement the fullscreen non-dismissible Locker Screen that covers locked apps. Pre-inflated at service start to meet the <200 ms latency budget. Uses plain XML (not Compose) per `research.md` R10 (Compose-in-WindowManager-overlay has lifecycle pitfalls).

## Context

- **Spec FR-001, FR-002.**
- **Research R2 (flags), R10 (XML over Compose).**
- **No dependency on crypto / repos / UI** - this is a pure view-layer primitive. `AppLockerAccessibilityService` (WP10) uses it.

## Subtasks

### T040 - `res/layout/locker_overlay.xml`

**Purpose.** The view that covers the locked app.

**Files to create:**
- `app/src/main/res/layout/locker_overlay.xml`
- `app/src/main/res/drawable/overlay_gradient.xml` (subtle brand background)
- `app/src/main/res/values/colors.xml` (brand colours if not in WP01)

**Implementation steps:**
1. `FrameLayout` root with `android:layout_width="match_parent"`, `match_parent` height, `android:fitsSystemWindows="false"`, background gradient for brand polish.
2. Centre `LinearLayout` (vertical) containing:
   - `ImageView` (96dp) - locked-app icon placeholder; populated programmatically in `OverlayManager.show`.
   - `TextView` app-name, 24sp, bold.
   - `TextView` package-name, 12sp, monospace, 70% alpha.
   - `TextView` lock-state ("Locked" with countdown if set), 16sp.
   - `Button` "Ask Guardian" (Material-styled, 56dp height), with `android:id="@+id/btn_ask_guardian"`.
   - `TextView` small hint: "Your Guardian will receive a secure request link."
3. Secondary bottom `LinearLayout` (horizontal) with:
   - A subtle Krypt logo + "Krypt protects this app" small text.
4. Use `?attr/colorSurface`, `?attr/colorOnSurface` Material attributes; overlay respects light/dark system theme.
5. IDs for every interactive view; no hardcoded strings (all in `strings.xml`).

**Validation.** Preview in Android Studio. Manual overlay-device test (after T044).

### T041 - OverlayManager core (singleton, show/hide/update)

**Files to create:**
- `app/src/main/java/com/krypt/app/service/OverlayManager.kt`

**Implementation steps:**
1. `@Singleton class OverlayManager @Inject constructor(@ApplicationContext private val ctx: Context)`:
   - Private `windowManager: WindowManager` from `ctx.getSystemService`.
   - Private `overlayView: View?` (pre-inflated in `preinflate()`; `null` until then).
   - Private `isShowing: Boolean` + private `currentPackage: String?`.
2. `fun preinflate()` - inflate R.layout.locker_overlay ONCE, cache `overlayView`. Called from `AppLockerAccessibilityService.onServiceConnected` (WP10).
3. `fun show(pkg: String, displayName: String, iconDrawable: Drawable?)`:
   - If `isShowing && currentPackage == pkg`: return (no-op).
   - If `isShowing && currentPackage != pkg`: call `updateContent(pkg, displayName, iconDrawable)` (swap without remove).
   - Else: build `WindowManager.LayoutParams` per T042; `windowManager.addView(overlayView, params)`; `isShowing = true`.
   - `currentPackage = pkg`.
4. `fun hide()`:
   - If not showing: return.
   - `windowManager.removeView(overlayView)`.
   - `isShowing = false; currentPackage = null`.
5. `fun updateContent(pkg, displayName, iconDrawable)` - just sets view fields; no WM touch.
6. All public methods posted to main thread via `Handler(Looper.getMainLooper())`.

**Validation.** Covered by T044.

### T042 - Layout params + immersive-mode flags

**Implementation steps (inside OverlayManager.buildLayoutParams):**

```
fun buildLayoutParams(): WindowManager.LayoutParams {
  val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY  // API 26+
  val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
              WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
              WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
              WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
              WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
              WindowManager.LayoutParams.FLAG_SECURE

  return WindowManager.LayoutParams(
    MATCH_PARENT, MATCH_PARENT, type, flags, PixelFormat.TRANSLUCENT
  ).apply {
    gravity = Gravity.TOP or Gravity.START
    // Cover status + nav bars:
    layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS  // API 28+
  }
}
```

Immersive-mode application once attached:

- API 30+: `overlayView.windowInsetsController?.hide(WindowInsets.Type.systemBars())`. `overlayView.windowInsetsController?.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`.
- API 29: `overlayView.systemUiVisibility = SYSTEM_UI_FLAG_IMMERSIVE_STICKY or SYSTEM_UI_FLAG_FULLSCREEN or SYSTEM_UI_FLAG_HIDE_NAVIGATION or SYSTEM_UI_FLAG_LAYOUT_STABLE or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION` (deprecated but still works on API 29).

Back-key consumption: override `dispatchKeyEvent` on the overlayView's root (subclass `FrameLayout` into `LockerOverlayRoot` or attach via `setOnKeyListener` that always returns true for `KEYCODE_BACK`).

**Validation.** Manual test: show overlay, press Back; observe overlay does NOT dismiss.

### T043 - update/hide ergonomics + threading

**Implementation steps:**

1. All public methods (`preinflate`, `show`, `hide`, `updateContent`) are `synchronized(this)` AND post their work to main-thread via `Handler(Looper.getMainLooper()).post { ... }` if called from a background coroutine. The Accessibility Service is free to call `show` from `onAccessibilityEvent` which IS on the main thread already - in that case, post synchronously.
2. `fun bindAskGuardian(onClick: (pkg: String) -> Unit)` - the `MainActivity` or WP10 wires this once at pre-inflation time. When tapped, the callback receives `currentPackage` and opens the request-URL Share sheet (logic in WP14; OverlayManager just fires the callback).
3. Graceful double-remove: `windowManager.removeView` throws `IllegalArgumentException` if the view isn't attached. Guard with `if (isShowing)`.

**Validation.** Smoke pass in T044.

### T044 - androidTest: OverlayManager lifecycle

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/service/OverlayManagerTest.kt`

**Implementation steps:**
1. Requires `SYSTEM_ALERT_WINDOW` permission granted via `UiAutomation` or adb pre-grant in the test setup.
2. `@Test fun showThenHideRemovesView`:
   - `manager.preinflate()`, `manager.show("com.example", "Example", null)`.
   - Query the `WindowManager` view hierarchy via `getViewHierarchy` (via reflection on `WindowManagerGlobal.getInstance().getViewRootNames()` - fragile; alternative: assert by running the test in a paused state and screenshot).
   - Simpler: flip a test-only flag via `manager.isShowingForTest` and assert.
   - `manager.hide()`; assert `isShowingForTest == false`.
3. `@Test fun showPkgThenSamePkgIsNoop`:
   - `show("a", "A", null)`, `show("a", "A", null)`; internal view-attach counter increments only once.
4. `@Test fun showPkgThenOtherPkgUpdatesContent`:
   - `show("a", "A", null)`, then `show("b", "B", null)`; assert view-attach count is still 1, but `currentPackage == "b"`.

**Validation.** Integration against a real `WindowManager` on a connected device. If CI has no device, mark as `@LargeTest` and run locally for PR approval.

## Test Strategy

- **Unit (JVM):** NotPractical (WindowManager is an Android framework service).
- **Instrumented (androidTest):** T044.
- **Manual:** Quickstart US-3 walkthrough once WP10 connects it.

## Definition of Done

- [ ] XML layout renders in Studio preview without errors.
- [ ] `OverlayManager` exposes `preinflate / show / hide / updateContent / isShowing / bindAskGuardian`.
- [ ] Layout params use exactly the flag set from T042 (review line-by-line).
- [ ] Back-key consumed in overlay-view key dispatch.
- [ ] `OverlayManagerTest` 3/3 green on a device.
- [ ] Overlay respects dark mode (test by toggling system theme and re-showing).

## Risks + Edge cases

- **OEM divergence on gesture-nav inset handling.** Some OEMs (MIUI, EMUI) leave a status-bar-height blank at top even with immersive flags. Acceptable for v1; document in known issues.
- **`addView` on a detached view.** If `overlayView` is still attached (isShowing=true) from a previous call and we call `addView` again, we get IllegalStateException. Guard on isShowing.
- **Rotation.** Overlay's `LayoutParams` has no orientation constraint; the view re-lays-out on rotation. Manual-test both orientations.
- **Pre-inflation from non-main thread.** `LayoutInflater.from(ctx).inflate` requires the main thread. `preinflate()` must be called from `onServiceConnected` (main thread) or post to `Handler(Looper.getMainLooper())`.
- **`FLAG_SECURE` prevents screenshots.** By design. Users trying to screenshot around the lock reveal they're motivated - that's a feature.

## Reviewer Guidance

- Paste a screen-recording or photo of the overlay covering a test app (WhatsApp on test device).
- Verify Back/Home/Recents do not dismiss (record a video demonstration).
- Inspect `WindowManager.LayoutParams` flags in a log dump; cross-check against T042 list.
- Confirm the overlay layout has no literal strings in XML (all in `strings.xml`).

## Next command

```
polaris implement WP09 --base WP01
```
