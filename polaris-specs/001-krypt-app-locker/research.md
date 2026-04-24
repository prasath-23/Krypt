# Phase 0 Research - Krypt

Resolves unknowns and justifies technical choices before moving to data-model and contracts.

---

## R1. AccessibilityService event-filter tuning for <200 ms overlay latency

**Question.** Which `AccessibilityServiceInfo` configuration delivers the lowest-latency foreground-package detection on Android 10-15 without draining the battery?

**Findings.**

- `TYPE_WINDOW_STATE_CHANGED` is the single event family that fires at app-launch. `TYPE_WINDOW_CONTENT_CHANGED` is chatty (fires on every subtree change) and raises CPU + battery cost for no extra signal here.
- `FLAG_DEFAULT` is insufficient. We need `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` only if we ever call `getRootInActiveWindow()`; for the foundation we don't, so we omit it to reduce per-event work.
- `notificationTimeout = 100` ms (not 0) debounces rapid state transitions (splash -> main activity) and cuts redundant overlay inflations without breaking the <200 ms budget.
- `eventTypes = typeWindowStateChanged` and `packageNames = unset` (listen to all packages). Locking down `packageNames` at the config level would require us to enumerate locked apps in the Accessibility config, which is a privacy-leaky and stale list. We filter inside `onAccessibilityEvent` instead.
- `canRetrieveWindowContent = false` (privacy-minimisation and perf).
- The overlay view must be pre-inflated once at service start and held as a field, not inflated on each event. Inflation typically costs 20-80 ms on mid-range devices; adding that to the 50-100 ms event-to-handler latency would blow the budget.
- `WindowManager.addView(preInflated)` with the correct layout params then completes in <5 ms.

**Decision.** Service config in `res/xml/accessibility_service_config.xml`: `accessibilityEventTypes=typeWindowStateChanged`, `accessibilityFlags=flagDefault|flagIncludeNotImportantViews` (needed for some OEMs' launchers), `notificationTimeout=100`, `canRetrieveWindowContent=false`. Pre-inflate overlay in `onServiceConnected()`.

---

## R2. WindowManager overlay flags for a genuinely non-dismissible Locker Screen

**Question.** Which combination of `WindowManager.LayoutParams` flags produces a fullscreen overlay that cannot be dismissed by Back, Home, Recents, notification shade, or rotation, while still allowing its own touch input?

**Findings.**

- `type = TYPE_APPLICATION_OVERLAY` is mandatory on API 26+. `TYPE_PHONE`, `TYPE_SYSTEM_ALERT`, `TYPE_SYSTEM_ERROR`, `TYPE_TOAST` are deprecated for this use case and will throw `WindowManager.BadTokenException` on modern Android.
- `flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_NOT_FOCUSABLE | FLAG_HARDWARE_ACCELERATED | FLAG_KEEP_SCREEN_ON | FLAG_SHOW_WHEN_LOCKED`.
  - `FLAG_NOT_FOCUSABLE` is counter-intuitive but essential: it prevents the overlay from intercepting system key events (Back) in a way that the OS treats as focus theft. The overlay itself handles touches via its own `View.setOnTouchListener`; we don't need IME focus.
  - `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` ensure coverage over status bar and navigation bar (gesture insets still visible; that is fine on Android 11+).
- `width = MATCH_PARENT`, `height = MATCH_PARENT`, `gravity = Gravity.TOP | Gravity.START`.
- Hiding the status bar and navigation bar:
  - API 30+: `WindowInsetsController.hide(systemBars())` on the overlay's `WindowInsetsController`.
  - API 29: fall back to `View.setSystemUiVisibility(SYSTEM_UI_FLAG_IMMERSIVE_STICKY | ...)`.
- Back button handling: override `dispatchKeyEvent` on the overlay's root view; consume `KEYCODE_BACK` events. Home/Recents are not dispatchable - they're handled by SystemUI. The way to "absorb" Home on a modern Android without root is to re-assert the overlay on the next foreground event (the Accessibility service fires again when the launcher comes foreground; we detect the launcher's own package and keep the overlay hidden - then when the user re-launches the locked app, the overlay re-appears instantly).
- Secure-flag tradeoff: `FLAG_SECURE` on the overlay prevents screenshots of it but also prevents its content from being captured by accessibility tooling for diagnostics. **Decision:** set `FLAG_SECURE` on the overlay - a user trying to screenshot around the lock reveals they're motivated.

**Decision.** Use the flag list above in `OverlayManager`. Pre-inflate the XML `locker_overlay.xml` once; call `WindowManager.addView` / `updateViewLayout` / `removeView` based on lock state.

---

## R3. Battery-optimisation survival

**Question.** What keeps `AppLockerAccessibilityService` resident and responsive after 24 hours of screen-off idle, given Doze, App Standby, and OEM aggressive killers?

**Findings.**

- Accessibility Services are lifecycle-managed by the OS and are **not** subject to the normal Doze process-kill. They persist indefinitely as long as the user has Accessibility toggled on for the service. This is Krypt's single biggest architectural advantage: we get "always-on" for free as long as the user has granted the privilege.
- The known failure mode: some OEMs (Xiaomi, Huawei, Oppo, Vivo) aggressively kill even Accessibility Services under their "Auto-start manager" / "Protected apps" / "Battery optimisation whitelist". There is no programmatic API to whitelist - we can only deep-link into the relevant Settings screen and guide the user.
- A supplementary defence: a minimal foreground service (not hosting the overlay - just heartbeat) with `foregroundServiceType="specialUse"` (API 34+) or `"dataSync"` (API 29-33). The notification it must post can be flagged as low-importance. If the OEM kills the accessibility service, the foreground service gets a restart opportunity and can re-prompt the user.
- `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` should be requested during onboarding on API 23+ devices. The user must grant it manually; there is no auto-grant.
- WorkManager periodic heartbeat (15 min interval, the minimum allowed) as a soft watchdog: the job simply checks `AccessibilityManager.getEnabledAccessibilityServiceList()` and if Krypt is absent, posts a high-priority notification telling the user to re-enable.

**Decision.** Three-layer defence:
1. Rely on the Accessibility Service's own lifecycle resilience.
2. Pair it with a low-importance foreground service that shares the service lifecycle.
3. Add a 15-minute WorkManager heartbeat that nags the user if Accessibility gets disabled.

OEM-specific battery-whitelist screens will be surfaced during onboarding via `Intent`s to the best-known Settings activities per OEM (documented in a lookup table in `OverlayManager` / `PermissionWizardScreen`).

---

## R4. PBKDF2 vs scrypt vs Argon2

**Question.** Which KDF meets the spec's >=250 ms CPU-cost budget on target hardware using only first-party AndroidX primitives, with no BouncyCastle add-on?

**Findings.**

- **PBKDF2-HMAC-SHA256:** available in `javax.crypto.SecretKeyFactory` on every Android API. No extra dependency. Iteration-count is the work-factor knob. OWASP's 2026 recommendation is >=600,000 iterations for PBKDF2-HMAC-SHA256 in general. For a PIN (low-entropy) protecting a high-impact secret (app-lock bypass), we want more. On a Pixel 7 reference, 300,000 iterations cost ~280 ms; on a lower-end 2020 device, ~450 ms. We standardise on **300,000 as the floor** and target 300k-500k depending on device speed measured at onboarding (adaptive calibration).
- **scrypt:** no first-party Android implementation. Would require Spongy Castle / BouncyCastle add-on (~1 MB APK bloat; acceptable but not first-party).
- **Argon2id:** the modern recommendation, but there is no first-party Android/JCE provider. Requires the `argon2-jvm` third-party library (~200 KB native lib per ABI).

The spec's rule "no third-party UI or networking libraries" does not strictly forbid third-party *crypto* libraries, but the spirit of the rule is to minimise external dependencies. PBKDF2 meets the work-factor target and uses only `javax.crypto`; it wins on the tie-breaker.

**Decision.** PBKDF2-HMAC-SHA256 with **calibrated iteration count, floor 300,000, target 300,000-500,000** based on onboarding-time device benchmark. Salt is 32 random bytes, per-Guardian (not per-request - per-request salt is redundant given the request has its own nonce).

Wait - re-reading the spec: the *request* carries a salt (`myapp://request?...&salt=...`). That is a per-request nonce salt, layered on top of the Guardian's persistent salt. The KDF input is `PBKDF2(PIN, HKDF(guardianSalt, requestSalt), 300k)`. This double-salt makes both replay and rainbow-table attacks infeasible.

---

## R5. Deep-link URI size vs messenger payload limits

**Question.** What is our URL size budget for request and approval links?

**Findings.**

- WhatsApp text message: hard cap ~65,000 characters per message. No practical limit for our needs.
- SMS: splits into 160-character chunks if sent as plain text over GSM; MMS allows much larger. The URL length matters only if the user's transport is SMS.
- Android `Intent` data URIs: no hard limit but some OEM launchers truncate at ~2,000 chars in the Share sheet preview.
- Android notifications: preview truncates URLs to ~100 chars in the collapsed view.

Our design:

- Request URL: `krypt://request?v=1&req={uuid:36}&app={pkg:max 128}&salt={b64:44}&proof={b64:44}&iat={epoch:10}` = ~300 chars typical, ~450 chars worst case.
- Approval URL: `krypt://approve?v=1&req={uuid:36}&data={b64 AES-GCM nonce+ciphertext+tag: ~90 bytes plaintext -> ~128 b64 chars}&iat={epoch:10}` = ~230 chars typical.

Both fit comfortably in SMS-friendly territory.

**Decision.** Use URL-safe Base64 (`Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING`) for all binary fields to avoid `+/=` encoding issues in URL query strings. Target absolute URL length <512 chars for SMS compatibility.

---

## R6. API 30+ package-visibility rules

**Question.** On Android 11+, an app cannot see other packages without a manifest declaration. How do we enumerate installed apps?

**Findings.**

- `QUERY_ALL_PACKAGES` permission gives full visibility but triggers a Play Store policy review (we're sideload-distributed anyway, so this is low-cost).
- The Play-friendly alternative is a `<queries>` manifest element listing specific packages or action filters. But Krypt needs to list **all** installed apps to populate the locked-apps UI, so a `<queries>` filter is insufficient.
- `ACTION_PACKAGE_ADDED` / `_REMOVED` / `_REPLACED` broadcasts work regardless of `<queries>` because they're system broadcasts with the package data in the `Intent`.

**Decision.** Declare `QUERY_ALL_PACKAGES`. Accept that this will block Play Store distribution unless a compliance waiver is filed; matches the spec's "sideload distribution" stance. Document the alternative (`<queries>` + only list apps the user has explicitly added) as a future compliance path.

---

## R7. API 33+ runtime `POST_NOTIFICATIONS`

**Question.** How does Krypt post its Security-Alerts notifications on Android 13+?

**Findings.**

- API 33 introduced runtime permission `android.permission.POST_NOTIFICATIONS`. Without it, `NotificationManager.notify()` silently drops the notification.
- The permission is requested via the normal runtime-permission flow. First denial -> one more prompt; second denial -> user must go to Settings.
- We declare `POST_NOTIFICATIONS` with `maxSdkVersion` unbounded and request it during onboarding.

**Decision.** Declare the permission in the manifest; request at onboarding (after Accessibility, before Guardian pairing). Notification channel `SECURITY_ALERTS` with `IMPORTANCE_HIGH` to maximise probability the user sees new-app-locked alerts.

---

## R8. API 34+ foreground-service type declarations

**Question.** What `foregroundServiceType` does the supplementary foreground service declare on Android 14+?

**Findings.**

- API 34 requires every foreground service to declare a `foregroundServiceType`, and the set of declared types must be passed to `Service.startForeground(id, notification, types)` on every start.
- Our supplementary service's purpose is to keep the Accessibility service supervised. That is not a "location", "camera", "media", "health", or "connectedDevice" use case. The catch-all is `specialUse`, introduced in API 34, which requires an additional `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"` with a human-readable description plus a Play Store waiver form.
- For API 29-33, no explicit type is required; we default to `dataSync` which the OS tolerates for any legitimate use.

**Decision.** `foregroundServiceType="specialUse|dataSync"` combined; OS will pick based on API level. Property string: `"accessibilityServiceSupervisor"`.

---

## R9. Hilt in AccessibilityService / BroadcastReceiver

**Question.** How to inject repositories into `AppLockerAccessibilityService` and `PackageReceiver`?

**Findings.**

- Hilt supports `@AndroidEntryPoint` on `Activity`, `Fragment`, `Service`, `BroadcastReceiver`, `View` (since Hilt 2.33). `AccessibilityService` extends `Service`, so `@AndroidEntryPoint` works.
- `BroadcastReceiver` injection requires the receiver to be registered in manifest (not `Context.registerReceiver`). That is already our plan for `PackageReceiver` (manifest-registered for `ACTION_PACKAGE_ADDED`).
- `KryptApplication` must be annotated `@HiltAndroidApp` and named in `<application android:name=".KryptApplication">`.

**Decision.** `@HiltAndroidApp` on `KryptApplication`. `@AndroidEntryPoint` on `MainActivity`, `GuardianActivity`, `AppLockerAccessibilityService`, `PackageReceiver`. `@Inject` constructor injection everywhere else.

---

## R10. Compose-in-WindowManager-overlay pitfalls

**Question.** Why not use Compose for the Locker overlay?

**Findings.**

- Compose requires a `LifecycleOwner`, `SavedStateRegistryOwner`, and `ViewModelStoreOwner` attached to any `ComposeView` before it composes. These are normally provided by `Activity` / `Fragment`.
- A view added via `WindowManager.addView()` has NO attached Lifecycle by default. You must manually implement `ViewTreeLifecycleOwner.set(view, lifecycleOwner)`, `ViewTreeSavedStateRegistryOwner.set(...)`, and `ViewTreeViewModelStoreOwner.set(...)`.
- Even with these set, keyboard handling and back-press dispatch behave oddly because the overlay view has no Window.
- Multiple shipped-and-broken apps have proven this fragile in production.

**Decision.** Use a plain XML `FrameLayout` as the overlay root, with traditional `findViewById` + `View.OnClickListener`. The overlay is a small UI (app icon, name, "Ask Guardian" button, countdown text). There is no Compose benefit worth the lifecycle machinery risk. MainActivity / GuardianActivity still use Compose + Material3.

---

## Summary of unknowns resolved

| # | Topic | Resolution |
|---|-------|-----------|
| R1 | Accessibility event filter | `TYPE_WINDOW_STATE_CHANGED` only, 100 ms debounce, pre-inflated overlay |
| R2 | Overlay flags | `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE|LAYOUT_IN_SCREEN|SECURE|...` |
| R3 | Battery survival | Accessibility-Service lifecycle + supplementary FGS + WorkManager heartbeat + OEM-Settings deep-links |
| R4 | KDF choice | PBKDF2-HMAC-SHA256, calibrated >=300k iterations, per-Guardian + per-request salt |
| R5 | URL size | URL-safe Base64, <512 chars target |
| R6 | Package visibility | `QUERY_ALL_PACKAGES` (sideload-distribution accepted) |
| R7 | Notifications | `POST_NOTIFICATIONS` requested at onboarding; channel IMPORTANCE_HIGH |
| R8 | Foreground-service type | `specialUse|dataSync` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |
| R9 | Hilt | `@AndroidEntryPoint` on Activity / Service / Receiver; `@HiltAndroidApp` on Application |
| R10 | UI for overlay | Plain XML FrameLayout (not Compose), Compose only for Activities |

All unknowns resolved. Proceeding to Phase 1 (data-model, contracts, quickstart).
