# Specification: Manual App Lock + Permission Clarity

**Feature number:** 002
**Status:** Draft
**Target branch:** main
**Mission:** software-dev

## Problem Statement

Krypt's Subject device today suffers from two usability gaps that undermine its core promise:

1. **Permission state is opaque during onboarding.** A user grants Accessibility / Overlay / Device Admin / Notifications / Battery-Optimisation permissions in OS Settings, returns to Krypt, and has no immediate visual confirmation that the grant landed. The five-step onboarding treats every permission as equal and offers no way to skip the ones that are nice-to-have, so a user who declines Notifications today is stuck --- they cannot finish setup.

2. **Manual app-lock control is missing.** Krypt's Default-Deny posture auto-locks every newly installed app. But apps that were on the device *before* Krypt was installed are unlocked, and there is no UI to bring them under protection. The Subject currently has zero ability to extend coverage to existing apps without uninstalling and reinstalling them.

The Subject experience must remain Zero-Trust --- at no point can the Subject unlock anything without Guardian approval --- but the Subject **must** be able to *add* apps to the locked list themselves.

## Actors

- **Subject** --- the device owner whose installed apps are being protected. Operates this device, sees the new Home Screen and onboarding, drives all interactions described in this spec.
- **Guardian** --- separate party holding the PIN. Unchanged by this spec; only relevant in that the Subject's "attempted unlock" flow continues to dispatch `krypt://request` URLs to the Guardian.
- **Android OS** --- source of truth for permission grants. Krypt observes its state but never modifies it.

## User Scenarios

### US-1 --- Subject grants Accessibility permission and sees confirmation immediately
**Given** the Subject is on the Accessibility step of onboarding and the permission is currently denied (✗ red),
**when** they tap "Open Accessibility Settings", grant Krypt access in the system UI, and press Back,
**then** within 500 ms of returning to Krypt the indicator transitions from ✗ red to ✓ green via a smooth animated swap, and the top-of-screen mandatory-progress bar advances by one segment.

### US-2 --- Subject skips Notifications during onboarding and still completes setup
**Given** the Subject is on the Notifications step (an Optional step),
**when** they tap "Skip",
**then** onboarding advances to the next step without granting Notifications, the mandatory-progress bar is unaffected, and the app continues to function --- the only behavioural difference is that auto-lock notifications are silently suppressed when the permission is missing.

### US-3 --- Subject cannot complete setup without the three Mandatory permissions
**Given** the Subject has granted only Accessibility and Overlay (2 of 3 Mandatory),
**when** they reach the end of onboarding,
**then** the "Finish" button is disabled, the mandatory-progress bar shows 2/3, and the Battery-Optimisation step displays a ✗ red indicator and a "Open battery settings" CTA. They cannot navigate past until 3/3 is reached.

### US-4 --- Subject manually locks an existing installed app
**Given** the Subject is on the Home Screen, which lists every installed non-system app with a toggle, and Instagram is currently unlocked,
**when** they tap Instagram's toggle,
**then** the toggle animates to the locked (ON) state immediately, Krypt persists the lock, and the next attempt to launch Instagram is covered by the Locker Screen.

### US-5 --- Subject attempts to unlock and is routed to the Guardian flow
**Given** WhatsApp is currently locked (toggle ON) on the Home Screen,
**when** the Subject taps the toggle in an attempt to turn it OFF,
**then** the toggle does NOT change state --- it remains ON --- and instead Krypt builds a `krypt://request?...` URL for WhatsApp and opens the system Share sheet so the URL can be sent to the Guardian. The Subject acknowledges and is returned to the Home Screen with the toggle still ON.

### US-6 --- Subject finds an app via search
**Given** the Subject has 80+ installed apps,
**when** they type "ban" into the search bar at the top of the Home Screen,
**then** the list filters in real time to show only apps whose name contains "ban" (e.g., Banking, Uber Bangalore), each with their current lock state. Lock toggles work the same way in the filtered view.

### US-7 --- Auto-locked apps appear pre-toggled on the Home Screen
**Given** Krypt is configured and the Subject installs a new app called "Photos" via the Play Store,
**when** the Subject opens Krypt's Home Screen,
**then** "Photos" appears in the installed-apps list with its toggle already in the ON state, distinguishable in no other way from a manually-locked app.

## Functional Requirements

### FR-022 --- Permission step classification
Onboarding steps MUST be classified as either Mandatory or Optional:
- **Mandatory:** Accessibility, Overlay (`SYSTEM_ALERT_WINDOW`), Battery Optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- **Optional:** Device Admin, Notifications (`POST_NOTIFICATIONS`).

### FR-023 --- Live permission indicator
Every permission onboarding step MUST display a live indicator reflecting the current OS-level grant state:
- Granted: a ✓ glyph in a designated "success" green colour.
- Not granted: a ✗ glyph in a designated "error" red colour.
- The indicator MUST update without app restart whenever the OS state changes (verified on `onResume` at minimum, ideally also via `BroadcastReceiver` / observation hooks where feasible).

### FR-024 --- Smooth transition animation
When the indicator switches state, the swap MUST be animated (cross-fade or scale-up) and complete in 200--500 ms. Instant cuts are NOT acceptable.

### FR-025 --- Indicator scoping
The live permission indicators MUST appear ONLY within the onboarding permission steps. They MUST NOT be displayed on the Home Screen, settings, watchdog notification, or any other surface.

### FR-026 --- Mandatory progress
A progress indicator at the top of onboarding MUST show only Mandatory permission progress (e.g., "2 of 3"). Optional permissions MUST NOT contribute to this counter.

### FR-027 --- Setup completion gate
The user MUST be unable to mark onboarding complete until all three Mandatory permissions are granted. Optional permissions MUST NOT block completion.

### FR-028 --- Skip on optional steps
Optional permission steps MUST display a visible "Skip" control. Tapping Skip MUST advance onboarding to the next step without granting the permission and without affecting the Mandatory progress counter.

### FR-029 --- Graceful degradation when optional permissions absent
When an Optional permission is denied or skipped, dependent Krypt features MUST degrade silently:
- No `POST_NOTIFICATIONS` → no auto-lock notifications fired (no crash, no log spam).
- No Device Admin → uninstall remains possible (the existing protection is best-effort), but no other Krypt feature breaks.

### FR-030 --- Home Screen as installed-apps list
The primary Home Screen of the Subject app, after onboarding completes, MUST present a vertically scrollable list of every installed non-system app on the device. Each row MUST display: the app icon, the app's display name, and a Material toggle switch indicating its lock state.

### FR-031 --- Search affordance
The Home Screen MUST include a search field that filters the installed-apps list by case-insensitive substring match against the app's display name. Filtering MUST update in real time as the user types.

### FR-032 --- One-way Lock toggle (ON)
Tapping a toggle that is currently OFF (unlocked) MUST atomically: (a) move the toggle visually to ON, (b) persist the lock state via the existing `LockedAppsRepository`, (c) make subsequent launches of the target app covered by the Locker Screen. The transition MUST happen without dispatching any deep-link or share-sheet flow.

### FR-033 --- Attempted unlock dispatches Guardian request
Tapping a toggle that is currently ON (locked) MUST NOT change the toggle's state. Instead it MUST:
1. Build a `krypt://request?...` URL targeting the tapped app's package (re-using `UnlockRequestBuilder` from feature 001).
2. Open the system `ACTION_SEND` chooser with the URL as the body so the Subject can dispatch via WhatsApp / SMS / etc.
3. Return to the Home Screen with the toggle visibly unchanged.

The toggle MUST NOT flicker between ON/OFF/ON during the attempted-unlock flow.

### FR-034 --- Auto-lock state reflected
Apps locked by the existing auto-lock-on-install path (feature 001 WP08) MUST appear with their toggle in the ON state on the Home Screen, indistinguishable visually from a manually-locked app. The data source is the same `LockedAppsRepository`.

### FR-035 --- Manual-lock and auto-lock coexist
Auto-lock-on-install MUST remain active. The Home Screen list reflects the union of auto-locked and manually-locked apps; toggling a row changes state for that single app only.

### FR-036 --- System app filtering
The Home Screen list MUST exclude system apps (those flagged `FLAG_SYSTEM` or in a curated allowlist of OS launchers/services). The exclusion MUST be consistent with the auto-lock filter from feature 001 --- an app the auto-locker would skip is also absent from the Home list.

## Success Criteria

- **SC-009** --- A user who has granted all 3 Mandatory permissions sees the mandatory-progress bar reach 3/3 within 500 ms of the third grant being detected, on a representative mid-range device (Pixel 6 / Xiaomi Redmi Note 12).
- **SC-010** --- A user who declines Optional permissions can still complete onboarding and reach the Home Screen.
- **SC-011** --- Manually toggling an unlocked app to Locked covers the app's next launch within 200 ms (matches feature 001 SC-002).
- **SC-012** --- Attempting to unlock a locked app on the Home Screen produces a `krypt://request?...` URL whose body parses cleanly through `UnlockRequestParser` and matches the app's package.
- **SC-013** --- Search response: typing a 3-letter substring on a device with 200 installed apps filters the visible list within 100 ms.
- **SC-014** --- Permission indicator state remains correct after backgrounding Krypt for 30 seconds, granting a permission in OS Settings, and returning. Indicator updates within 500 ms of `onResume`.
- **SC-015** --- On a freshly-installed Krypt with 50 pre-existing apps, opening the Home Screen renders the full list within 1.5 seconds (icon resolution included).

## Key Entities

- **Permission state snapshot** --- for each permission key (Accessibility, Overlay, Battery, Device Admin, Notifications): {granted: Boolean, classification: Mandatory | Optional, last_observed_at: timestamp}.
- **Installed app row** --- for each non-system installed package: {package_name, display_name, icon_drawable, is_locked: Boolean}. `is_locked` is reactive over the existing `LockedAppsRepository`.
- **Mandatory progress** --- derived: count of Mandatory permissions in `granted = true` state, out of total Mandatory count (always 3 in v1).
- **Manual lock event** --- telemetry record for analytics if added later: {package, action: lock | attempted_unlock, at: timestamp}. Out of scope for v1 implementation but the data model must support it.

## Assumptions

- Android 10+ (`minSdk 29`) target --- matches feature 001's stack and constraints.
- Live permission detection uses `onResume` polling plus, where applicable, `AccessibilityManager.AccessibilityStateChangeListener` and `Settings.canDrawOverlays()`. Real-time push of permission grants is not universally supported across OEMs; `onResume` is the contract floor.
- The Subject's Guardian is already paired (or PIN already set per Amendment 1 of feature 001). Manual unlock attempts depend on `MasterKeyStore.isConfigured()` being true.
- The "Search" UX is a simple substring match; no fuzzy matching, no usage-frequency ranking in v1.
- App-icon resolution can be expensive for large lists; the implementation must page or lazy-load icons but the spec does not prescribe how.

## Out of Scope

- Custom unlock-duration on the Home Screen --- Guardian's approval URL still grants the default 15-minute window.
- Bulk lock / unlock operations.
- Grouping apps into categories or folders.
- Showing per-app usage statistics on the Home Screen.
- A "Manage permissions" surface outside onboarding (per Q&A: indicators only in onboarding).
- Re-running onboarding from a Settings menu (existing behaviour preserved; the new mandatory-progress bar applies only the first time).
- Telemetry of manual-lock events.
