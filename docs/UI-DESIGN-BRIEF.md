# Krypt - UI Design Brief

> **Who this is for:** a designer, or a design AI, producing a new UI for
> Krypt, the zero-trust offline app locker for Android.
>
> **The one hard constraint:** everything below the screens stays as it is.
> That covers the ViewModels and their states, the repositories, the
> crypto, the accessibility service, and the `krypt://` link formats. Every
> screen, field and action in this brief is backed by code that exists
> today. Layout, navigation, visual style and wording are yours to redesign.
> Section 4 lists the few things that cannot change.
>
> **Sources:**
> - `polaris-specs/001-krypt-app-locker/spec.md` (with Amendments 1-3);
> - `polaris-specs/002-manual-lock-and-permission-clarity/spec.md`;
> - the link contracts in `polaris-specs/001-krypt-app-locker/contracts/`;
> - the UI code in `app/src/main/java/com/krypt/app/ui/` and
>   `app/src/main/java/com/krypt/app/guardian/`;
> - the strings in `app/src/main/res/values/strings.xml`.
>
> Where this brief and the code disagree, the code wins.

---

## Contents

1. [The product in one page](#1-the-product-in-one-page)
2. [Who uses it](#2-who-uses-it)
3. [Design goals and key aspects](#3-design-goals-and-key-aspects)
4. [What is fixed and what is free](#4-what-is-fixed-and-what-is-free)
5. [Screen map and navigation](#5-screen-map-and-navigation)
6. [Rules on every screen](#6-rules-on-every-screen)
7. [Screens](#7-screens)
8. [Shared components and patterns](#8-shared-components-and-patterns)
9. [Key user journeys](#9-key-user-journeys)
10. [Words and messages](#10-words-and-messages)
11. [Data reference](#11-data-reference)
12. [Opportunities and non-goals](#12-opportunities-and-non-goals)
13. [Appendix: what each screen is wired to](#13-appendix-what-each-screen-is-wired-to)
14. [What to deliver](#14-what-to-deliver)

---

## 1. The product in one page

**Krypt locks apps on a phone, and only a trusted second person can unlock
them.**

- **Every app is locked by default.** Apps installed after Krypt are locked
  automatically, with a notification. Apps that were there before can be
  locked from Krypt's Home list.
- **Opening a locked app shows Krypt's lock screen instead.** The app itself
  never appears. The only way in is to **Ask Guardian**.
- **Unlocking works through a link, with no server involved.**
  1. *Ask Guardian* shares a `krypt://request` link through any messenger:
     WhatsApp, SMS, email, even copy and paste.
  2. The Guardian taps it on their own phone, chooses how much time to
     allow, types the PIN, and shares back a `krypt://approve` link.
  3. Tapping that link on the locked phone opens the app. No PIN is typed on
     that phone.
- **Time-limited access.**
  - **One time:** 15 min, 30 min, 1 hour, 2 hours, or 1-1440 minutes typed.
  - **Every day:** up to N minutes a day for D days (3, 7, 14, 30, or
    1-365 typed). Since Amendment 6 the minutes can instead come back in
    a period of any length from 5 minutes to 24 hours, by the clock from
    midnight: for example 30 minutes every 2 hours, or 5 minutes every 30
    minutes.
- **Fully offline.** Krypt has no internet permission at all. Nothing leaves
  the phone except the links people choose to share.

**The same app runs on both phones.** On the locked phone it is the locker;
on the Guardian's phone it is only the approval screen, opened from a link.

---

## 2. Who uses it

| Role | Who they are | Which screens they see |
|---|---|---|
| **Subject** | Owns the locked phone: a child, a person in recovery, or someone who wants to focus. Does **not** know the PIN | Lock screen; the unlock confirmation; notifications |
| **Guardian** | A parent, sponsor, therapist or accountability partner. Knows the PIN | Set Guardian PIN (once, on the Subject's phone); the Krypt Admin PIN gate and the Home list (on the Subject's phone); the approval screen (on their own phone) |
| **Administrator** | Whoever installs Krypt and grants its permissions. Often the Guardian | Onboarding (the permission wizard) |

The Subject may not have chosen this. They may be a child, or under clinical
supervision. The design must be **calm and respectful**, never punitive or
alarming. It must also be **firm**: no screen may suggest a way around the
lock.

---

## 3. Design goals and key aspects

### 3.1 Goals

1. **Clean and calm.** A lock screen is seen many times a day. It should feel
   quiet and clear, not like an error.
2. **Easy to understand.** The request and approval round trip through a
   messenger is unusual. Every step must say what just happened and what to
   do next.
3. **Trustworthy.** PIN screens must look secure and behave securely: masked
   digits, clear lockout timers, no PIN ever shown.
4. **Two audiences, one app.** The Subject sees the lock screen. The Guardian
   sees the approval screen and the Home list. Each must make sense without
   knowing the other side.
5. **Redesignable.** Screens are thin layers over ViewModels (section 13).
   Keep that split, so a redesign never touches the logic.

### 3.2 Key aspects to get right

- **The lock screen.**
  - It is the screen seen most often, and it replaces the locked app.
  - The very first frame is drawn from the window background before any UI
    code runs, so the design must look complete with only that background.
  - It has three variants: locked; today's time used up (or, with a
    shorter period, this period's, until the next one starts); daily time
    paused.
- **The Guardian approval screen.** It runs on the Guardian's own phone, and
  the Guardian opens it from a chat. It must answer three questions at a
  glance:
  - which app is being asked for;
  - what will this allow (one time, or every day or in a shorter period,
    how long);
  - "type your PIN to send".
- **The Home list.**
  - Every installed app has a lock switch and a status line: "Locked";
    "Unlocked, N min left" with **Lock now**; or daily time, with **End
    daily time**.
  - The list can hold 200 apps or more, so search matters.
- **Onboarding permissions.**
  - Five system permissions, two of them mandatory.
  - Each step shows a live granted or not-granted indicator that updates the
    moment the user comes back from system Settings.
- **Hand-offs to the system.** Many actions leave Krypt: the share sheet, the
  system Settings pages, and opening the unlocked app. Design the moment
  before and after each one.
- **Phone first.** Android 10 to 15, portrait first, from about 320 dp wide.
  Large text (font scale up to 200%) must not break any screen.
- **Accessible.** TalkBack labels on every icon, state never shown by colour
  alone, 48 dp touch targets, contrast at WCAG AA or better.
- **Light and dark.** Both themes ship today (`ui/theme/Color.kt`). Keep
  both.

---

## 4. What is fixed and what is free

### 4.1 Fixed: the redesign must keep these

| Fixed thing | Why |
|---|---|
| **No network.** No web fonts, remote images, analytics, crash reporting or remote config. Every asset ships in the APK | The manifest must not declare `INTERNET` (FR-005; `verifyManifest` fails the build) |
| **No third-party UI libraries.** Jetpack Compose with Material 3 and Material Icons only. Bundled font files are fine | `CONTRIBUTING.md`: first-party AndroidX / Jetpack / Kotlin / Hilt only |
| **Every visible string in `res/values/strings.xml`**, with `plurals` for counts | Lint-enforced (WP18); ready for translation |
| **The ViewModels, their state classes and their actions** (section 13) | They are the app's "backend" for the UI |
| **The link formats** `krypt://request?...` and `krypt://approve?...`, and the shared message must carry the whole link | Defined by the contracts; the other phone parses them |
| **The lock screen leads only to the home screen**, never back into the locked app. Back and "Go to home screen" both go to the launcher | FR-001, FR-002 |
| **No PIN entry anywhere on the Subject's unlock path.** The lock screen has no PIN field; approvals are applied silently | FR-018 |
| **PIN handling:** digits only; always masked; never kept in saved state; cleared after use; 4-6 digits at setup | FR-017 |
| **The approval confirmation:** a green flash of at least 500 ms, a short vibration and a toast naming the app; no buttons, no fields | FR-021 |
| **Permission indicators appear only in onboarding**, and their change is animated in 200-500 ms | FR-024, FR-025 |
| **The onboarding progress counts only the 2 mandatory permissions**; Finish stays disabled until both are granted; only optional steps can be skipped | FR-026, FR-027, FR-028 |
| **The Home switch reflects the lock setting**: on = locked. It stays on while a Guardian unlock or daily time runs, because the app locks again by itself | Amendment 3 |
| **The Guardian approval screen blocks screenshots** (`FLAG_SECURE`) | The PIN is typed there |
| **Leaving Krypt closes the Home list.** Coming back asks for the PIN again | Amendment 2 |
| **The PIN lockout schedule** (section 11.3) | `PinAttemptLimiter` |

### 4.2 Free: change any of these

- The layout, spacing, typography, iconography, illustrations and motion of
  every screen, within the timing rules above.
- The colour palette. Today: deep purple and amber (section 8.1).
- How onboarding is presented: one step per page as today, a checklist, or a
  stepper.
- How the Home list is organised: sorting, sections (locked first,
  unlocked now, daily), a filter, sticky search. All of this works on the
  data the list already has.
- Wording, as long as the meaning stays and the text lives in
  `strings.xml`. Mind the test note in section 14.
- Small UI-only additions that need no new logic, such as an explanation
  sheet, an empty state or an illustration.

### 4.3 Not available without new logic

These are listed in section 12.2 so the design does not promise them:
changing or recovering the PIN, a settings screen, usage statistics,
schedules, several Guardians, and biometric unlock.

---

## 5. Screen map and navigation

### 5.1 The launcher icon: one route, decided by state

`MainActivity` shows the first screen in this list that applies:

```
1. Onboarding not finished          -> Onboarding (permission wizard)
2. No Guardian PIN set yet          -> Set Guardian PIN
3. Krypt not unlocked this visit    -> Krypt Admin PIN gate
4. Otherwise                        -> Home (installed-apps list)
```

Leaving Krypt (Home button, Recents, screen off) returns it to step 3.

### 5.2 Other ways in: each its own screen, opened by the system

| Entry | Opened by | Screen |
|---|---|---|
| A locked app comes to the front | Krypt's accessibility service | **Lock screen** (`ui/lock/LockScreenActivity.kt`), in its own task and hidden from Recents |
| A `krypt://request` link is tapped | Any messenger | **Guardian approval** (`ui/guardian/GuardianActivity.kt`) |
| A `krypt://approve` link is tapped | Any messenger | **Approval confirmation** (`subject/ApprovalTrampolineActivity.kt`): translucent, no UI except the flash and a toast |
| "Open in Krypt" from the share sheet or text selection | Any app | **Link receiver** (`ui/link/LinkReceiverActivity.kt`): no UI, opens the link it finds, or shows a toast |
| "Paste link" / "Paste approval link" buttons | Krypt itself | Opens the copied link as above |
| Notifications | Krypt | "New App Protected" opens Krypt; "Krypt is off" and "Krypt has stopped" open Accessibility settings |

### 5.3 Two phones, one app

- On the **Subject's phone** all of the above is used.
- On the **Guardian's phone** Krypt is installed only to open request links.
  If the Guardian opens Krypt from the launcher, they land on onboarding,
  whose top bar offers **Paste link** for exactly this case. A clearer
  "I'm a Guardian" path is an opportunity (section 12.1).

---

## 6. Rules on every screen

- **Theme.**
  - Material 3 through `KryptTheme` (`ui/theme/`), following the system's
    light or dark setting.
  - Drawn edge to edge, with transparent system bars.
  - Shapes are rounded at 12 / 16 / 24 dp; typography is Material 3's
    default today.
- **Text.** Every label, message and content description is a string
  resource. Use plurals for counts ("1 attempt left" / "2 attempts left").
- **PIN fields.**
  - Numeric keypad (`NumberPassword`), always masked, digits only.
  - Up to 6 digits on the Krypt screens; up to 8 typed on the Guardian
    screen.
  - Disabled during a lockout, with a live countdown.
- **Slow checks.** Checking a PIN deliberately takes about 0.3 s or more
  (PBKDF2). Show progress: a spinner in the button, or "Verifying PIN...".
- **Errors.** Inline, under the field or in the status area, in the error
  colour, with plain words (section 10.3). Snackbars are used only for "no
  link found".
- **Hand-offs.**
  - **Share:** the Android share sheet, with Krypt's own "Open in Krypt"
    left out.
  - **Settings:** system Settings intents.
  - **Launch:** opening the unlocked app.
  - In every case, say what will happen before it happens.

---

## 7. Screens

Each screen is described the same way:

- **Purpose**: why the screen exists.
- **Who**: who sees it.
- **Shows**: what is on it.
- **Actions**: what can be done.
- **States**: the variants to design.
- **Rules**: behaviour the design must keep.
- **Ideas**: optional improvements that need no new logic.

### 7.1 Onboarding: permission wizard

**Purpose.** Grant the system permissions Krypt needs to lock apps.

**Who.** The Administrator, on the Subject's phone, once.

**Code.** `ui/main/OnboardingScreen.kt`, `ui/onboarding/OnboardingViewModel.kt`,
`ui/onboarding/OnboardingStep.kt`.

**Shows**

- Title "Welcome to Krypt", and a **Paste link** action in the top bar for a
  Guardian-only phone.
- **Mandatory progress:** a bar and the text "Required permissions: N of 2".
  It counts only the mandatory permissions.
- "Step N of 5".
- The current step: title, explanation, a button that opens the right
  Settings page, and a live **granted / not granted** indicator (an animated
  check or cross, with a TalkBack label).
- The navigation row: **Back**; **Skip** on optional steps only; **Next**,
  or **Finish** on the last step.

**The five steps**, in this order:

| # | Step | Kind | Button opens |
|---|---|---|---|
| 1 | Accessibility access | Mandatory | Accessibility settings |
| 2 | Display over other apps | Mandatory | Overlay permission for Krypt |
| 3 | Battery optimisation | Optional | Ignore-battery-optimisation prompt |
| 4 | Device Admin | Optional | Activate Device Admin (with an explanation) |
| 5 | Notifications | Optional | The notification permission prompt (Android 13+) |

The step texts are the `onboarding_step*` strings, e.g. *"Krypt needs
Accessibility access so it can detect when a locked app is about to open and
block it. Krypt does not read your app content."*

**States**

- Each step: granted or not granted.
- Progress at 0, 1 or 2 of 2.
- Last step with Finish disabled (fewer than 2 mandatory granted) or enabled.
- "No Krypt link found..." snackbar after Paste link.

**Rules**

- The indicator updates when the user returns from Settings (on resume), with
  a 200-500 ms animation.
- **Next** is always allowed; the gate is only at **Finish**.
- Skipping an optional step changes nothing in the progress.
- Without Notifications, alerts are dropped silently. Without Device Admin,
  Krypt can simply be uninstalled.

**Ideas**

- An overview first ("2 things Krypt needs, 3 optional").
- A checklist view that shows all five steps with their indicators. This is
  allowed, because the indicators stay inside onboarding.
- OEM help on the battery step. `ui/main/OemBatteryLinks.kt` already has
  deep links for Xiaomi, Huawei, Oppo, Vivo and Samsung, but no screen uses
  them yet.

### 7.2 Set Guardian PIN

**Purpose.** The Guardian sets the PIN on the Subject's phone, once.

**Who.** The Guardian, holding the Subject's phone.

**Code.** `ui/setup/PinSetupScreen.kt`, `ui/setup/PinSetupViewModel.kt`.

**Shows**

- Title "Set Guardian PIN".
- The text: *"Hand this device to your Guardian. They will set a 4-6 digit
  PIN that only they know. You will not see the PIN - this is what protects
  your locked apps."*
- **PIN** and **Confirm PIN** fields: masked, numeric, 6 digits at most.
- **Save PIN**, enabled at 4 or more digits when both fields match.

**States**

- **Idle.**
- **Saving:** a progress bar and "Securing PIN...". The fields are locked;
  calibration can take a moment on first run.
- **Error:**
  - "PIN must be at least 4 digits.";
  - "PIN must contain digits only.";
  - "The two PINs don't match.";
  - "Could not save the PIN. Please try again.".
  Typing again clears the error.
- **Done:** "Saved. Hand the phone back to the owner." The app then moves on
  to the PIN gate.

**Rules**

- The PIN is never shown, never saved in instance state, and wiped when the
  screen closes.
- Rotating the screen clears the fields on purpose.

**Ideas**

- Present this as a deliberate hand-over moment: "Give the phone to your
  Guardian", then the PIN, then "Give the phone back".
- Use a PIN pad with dots instead of text fields. It must stay masked and
  numeric, 4-6 digits.

### 7.3 Krypt Admin PIN gate

**Purpose.** Keep the Home list, where locks are changed, behind the
Guardian PIN.

**Who.** The Guardian, on the Subject's phone. The Subject sees it whenever
they open Krypt.

**Code.** `ui/main/AppEntryPinScreen.kt`, `ui/main/AppEntryViewModel.kt`.

**Shows**

- Title "Krypt Admin" and the text "Enter the Guardian PIN to manage locked
  apps."
- A **PIN** field and an **Unlock** button, enabled at 4 or more digits. The
  button shows a spinner while the PIN is checked.
- **Paste link**: opens a copied request or approval link.

**States** (`AppEntryState`)

| State | Shows |
|---|---|
| Idle | The empty field |
| Validating | A spinner in the button; the field is disabled |
| WrongPin(n) | "Wrong PIN. n attempts left."; the field is cleared |
| LockedOut(ms) | "Too many wrong PINs. Try again in N s / N min." with a live countdown; the field and button are disabled |
| NotConfigured | "The Guardian PIN isn't set up on this device." |
| Success | Goes to Home |

**Rules**

- This screen shares its attempt counter with the Guardian approval screen
  (section 11.3).
- Leaving Krypt returns here.

**Ideas**

- A neutral, adult tone: this screen is for the Guardian, and the Subject
  will see it too.
- Explain Paste link in one line: "Got a Krypt link that won't open? Copy
  it, then tap Paste link."

### 7.4 Home: installed-apps list

**Purpose.** Choose which apps are locked, and see or end what is unlocked
right now.

**Who.** The Guardian, after the PIN gate.

**Code.** `ui/main/HomeScreen.kt`, `ui/home/HomeViewModel.kt`,
`ui/home/InstalledAppRow.kt`, `ui/home/AppRowStatus.kt`.

**Shows**

- A top bar, "Krypt".
- A **Search apps** field: case-insensitive substring match on the app name,
  updating as the user types.
- One row per installed app, system apps excluded: icon (48 dp), name,
  status line, lock switch.

**The status line** under each app name (`AppRowStatus`):

| Status | Line | Action on the row |
|---|---|---|
| NotLocked | (none) | - |
| Locked | "Locked" | - |
| Unlocked(minutes) | "Unlocked, N min left" (exact text: `home_status_unlocked`), counting down every second | **Lock now**: ends the unlock at once |
| Daily: Left | "Daily: N of M min left today, until Sep 28" (`home_status_daily_left`) | **End daily time** |
| Daily: UsedUp | "Daily: time's up today, until Sep 28" | **End daily time** |
| Daily: Paused | "Daily time paused: needs automatic date & time, until Sep 28" | **End daily time** |

Since Amendment 6 a daily line also carries `periodMinutes` (1440 for a
day), and a used-up one carries `backAtMs`, when the next period starts
(none for a day, or when the rule ends first). A rule counted in a shorter
period can then say when its time is back.

**Actions**

- **Switch off -> on:** lock the app (`onToggle`).
- **Switch on -> off:** remove the lock (`onToggle`). This is allowed here
  because the screen sits behind the Guardian PIN.
- **Lock now** (`onLockNow`) and **End daily time** (`onEndDailyTime`).
- The list refreshes when the screen resumes, to pick up installs and
  uninstalls.

**States**

- **Loading:** "Loading apps...".
- **No match:** `No apps match "abc"`.
- **The list:** rows with any mix of statuses.

**Rules**

- The switch shows the lock setting, so it stays **on** during an unlock or
  daily time.
- Apps locked automatically on install look the same as apps locked here.
- Some apps are never blocked even when locked: the home app, keyboards and
  the dialer. The list does not mark them today.

**Ideas**

- Sections or a sort order: "Unlocked now", "Daily time", "Locked", "Not
  locked".
- Filter chips (All / Locked / Not locked).
- A sticky search field.
- A summary line, e.g. "42 locked, 2 unlocked now".
- Confirm before removing a lock, because it opens the app to the Subject
  for good.
- Mark the always-allowed apps. The repository already skips them, but
  showing that in the list would need a small UI-side check.
- Today the "Locked" label is drawn in the success green. Choose colours
  that read as "protected" rather than "error" or "success" (section 8.1).

### 7.5 Lock screen

**Purpose.** Replace a locked app the moment it opens, and offer the one way
in: asking the Guardian.

**Who.** The Subject. It is seen often.

**Code.** `ui/lock/LockScreenActivity.kt`, `ui/lock/LockScreenViewModel.kt`;
the first frame is `res/drawable/lock_screen_background.xml` in the
`Theme.Krypt.LockScreen` theme.

**Shows**, centred, at most 360 dp wide, white text on the dark gradient:

- the app's icon (96 dp), its name, and its package name (small, monospace);
- **the reason** (`LockReason`):

| Reason | Title | Body |
|---|---|---|
| Locked | "This app is locked" | "Ask your Guardian for access. The app opens as soon as they approve." |
| DailyUsedUp | "Time's up for today" | "Today's N minutes are used up. Ask your Guardian if you need more time now." |
| DailyPaused | "Daily time is paused" | "This app's daily time needs Set time automatically. Turn it on in Settings, or ask your Guardian." |

Since Amendment 6 both daily reasons also carry `periodMinutes`, and
`DailyUsedUp` carries `backAtMs`: for a rule counted in a shorter period,
when the next period starts.

- **Ask Guardian**: the primary action, a full-width 56 dp button in amber;
- the request status under it (`RequestStatus`), below;
- **Paste approval link** (text button), with a hint when the clipboard has
  no approval link: "No approval link on the clipboard. Copy your Guardian's
  whole reply first.";
- **Go to home screen** (text button);
- a footer, "Krypt protects this app".

**Request status** after Ask Guardian:

| Status | Text |
|---|---|
| None | (nothing) |
| Preparing | "Preparing your request..." (the button is disabled) |
| Sent | "Request sent. When your Guardian approves, tap the link in their reply, or copy the reply and tap Paste approval link, and {app} will open. Requests expire after 5 minutes." |
| NotConfigured | "Krypt isn't set up yet. Open Krypt and set a Guardian PIN first." |
| Failed | "Couldn't create the request. Please try again." |

**What Ask Guardian does.** It opens the share sheet titled "Send unlock
request to Guardian", with this message:

> Krypt unlock request for {app}.
> Tap the link to review it. If it doesn't open, copy this whole message,
> open Krypt and tap Paste link.
> krypt://request?...

**Rules**

- **Back** and **Go to home screen** go to the launcher, never to the app.
- **No PIN field, ever.**
- When an approval arrives, the screen closes by itself and the app opens.
  If daily time becomes available while the screen is up, the screen
  closes. Since Amendment 6 that includes the moment the next period (or
  day) starts.
- If the reason changes while the screen is up, update it in place.
- **The first frame** is the window-background gradient alone. A redesign
  that changes the background must change the drawable to match, so the
  screen never flashes.

**Ideas**

- Make the reason visual: a lock, an hourglass for "time's up", a clock for
  "paused".
- Show a short step indicator after sending: "Request sent, then wait for
  the reply, then tap the link".
- A small "expires in 4:59" timer. The 5-minute lifetime is fixed; a
  countdown from the moment of sending can be computed in the UI.

### 7.6 Guardian approval screen

**Purpose.** On the Guardian's phone: review a request, choose what to
allow, prove the PIN, and send the approval back.

**Who.** The Guardian, from a link in a chat.

**Code.** `ui/guardian/GuardianActivity.kt`, `guardian/GuardianPinScreen.kt`,
`guardian/GuardianPinViewModel.kt`, `guardian/AccessForm.kt`.

**Shows** (`GuardianPinState.Ready`)

- Title "Unlock request".
- **The request card:** the app's **package name** (e.g.
  `com.instagram.android`) and "Approve unlocking this app for your Subject
  device?". The request carries only the package name, not the app's name or
  icon; see section 12.1.
- **Kind** (only when the request has `caps=daily`): chips **One time** /
  **Every day**.
- **Allow for:** chips 15 min / 30 min / 1 hour / 2 hours / **Set minutes**.
  Set minutes reveals a field, "Minutes (1-1440)", with the error "Enter a
  number of minutes from 1 to 1440."
- **For** (Every day only): chips 3 / 7 / 14 / 30 days / **Set days**. Set
  days reveals a field, "Days (1-365)", with the error "Enter a number of
  days from 1 to 365.", plus the hint: "Day 1 is today, and time already used
  today counts. For extra time today, choose One time."
- **Every** (Amendment 6; Every day only, and only when the request has
  `caps=periods`): 30 min, 1, 2, 4, 6 or 12 hours, a day (the default), or
  any period typed in hours, from 5 minutes to 24 hours ("1.5", "1:30").
  The minutes then come back every period, by the clock from midnight; a
  period that doesn't divide the day leaves a shorter last one, ending at
  midnight. Minute chips that aren't shorter than the period are hidden (a
  period of an hour or less gets 5, 10, 15 and 30 min), typed minutes are
  limited to the period's length, and the summary says "15 minutes an
  hour", "30 minutes every 2 hours" or "15 minutes every 90 minutes".
- **The summary:** "Allows: 30 minutes, one time", or "Allows: 1 hour a day
  for 7 days".
- **Enter PIN:** masked, numeric.
- **Verify & send approval**: enabled at 4 or more digits, with a valid
  choice and no lockout.

**States**

| State | Shows |
|---|---|
| Loading | A spinner while the link is read |
| FatalError | One message: "This unlock request has expired. Ask your Subject to send a new one." / "This unlock request appears corrupted." / "Could not read this unlock request." |
| Ready | The form above, with WrongPin ("Wrong PIN. n attempts left.") or Lockout ("Too many wrong PINs. Try again in N s / N min.", with a live countdown) under the PIN |
| Validating | A spinner and "Verifying PIN..." |
| Approved | The share sheet opens with the approval message; the screen shows "Approval sent. You can close this screen." |
| Unknown link | "Unknown Krypt link" / "Krypt can't open this link. Ask for a new unlock request." |

**The approval message** shared back:

> Krypt approval for {package}: {30 minutes, one time}.
> Tap the link to unlock it. If it doesn't open, copy this whole message,
> open the locked app and tap Paste approval link.
> krypt://approve?...

**Rules**

- Defaults: **One time, 15 minutes**. Every day defaults to 7 days.
- "Every day" is offered only when the request says the Subject's phone
  supports it, and periods shorter than a day only when it says so too
  (`caps=periods`).
- The choice is kept across wrong PINs and lockouts.
- Screenshots are blocked on this screen.
- A request expires 5 minutes after it was made, so speed matters.

**Ideas**

- Order the form as a sentence: "Allow {app} for {30 min} {one time}", then
  the PIN.
- Show how old the request is ("sent 1 min ago, expires in 4 min"). The UI
  can compute it from the request's `issuedAt` and `ttlSeconds`.

### 7.7 Approval confirmation (Subject's phone)

**Purpose.** Apply an approval silently and confirm it.

**Code.** `subject/ApprovalTrampolineActivity.kt`, `subject/UnlockSuccessEffect.kt`,
`res/layout/activity_approval_trampoline.xml`.

**What happens.** A translucent window opens. There are **no buttons and no
fields**. The approval is checked, then:

| Outcome | Feedback |
|---|---|
| One time | A green flash (500 ms), a 50 ms vibration, a TalkBack "Unlocked by Guardian", the toast "Unlocked {app} until 14:35", then the app opens |
| Every day, time left today | The same flash and vibration, the toast "{app}: 1 hour a day until Sep 28", then the app opens |
| Every day, today used up | The toast "{app}: daily time is set, but today's is already used up." (no flash) |
| Error | One toast (section 10.3), then close |

Since Amendment 6, a rule counted in a shorter period names it in the
toast ("{app}: 30 minutes every 2 hours until Sep 28", "{app}: 15 minutes
an hour until Sep 28"), and one already used up says "{app}: repeating time
is set, but it's used up for now".

**Ideas.** The flash colour (`#35C759`) and its shape can change: a
full-screen tint, a ring, or a check mark animation. It must stay at least
500 ms, stay accessible, and keep the vibration and toast.

### 7.8 System surfaces

These are part of the experience too.

| Surface | Content |
|---|---|
| Notification: new app locked (Security Alerts channel, high importance) | "New App Protected" / "{App} ({package}) has been locked by default". Tapping opens Krypt |
| Notification: protection off | "Krypt is off" / "Locked apps can be opened freely. Tap to turn Krypt Locker back on in Accessibility settings." Posted the moment Krypt's accessibility service is switched off, and taken down when it is switched back on |
| Notification: protection stopped (since Amendment 10) | "Krypt has stopped" / "Locked apps can open freely. Tap, then turn Krypt Locker off and on again to restart it." Posted by the 15-minute health check when Krypt is switched on in Settings but isn't running, and taken down when it runs again |
| Accessibility service description (system Settings) | `accessibility_service_description`: what Krypt watches, and that it sends nothing off the device. Since Amendment 8 it starts "Krypt needs Accessibility access to see which apps are on screen and block locked apps until your Guardian approves." (it was "…to see which app is being launched…"). Since Amendment 7 it also says: "When an app's time is nearly up, Krypt shows how long is left over it, and closes the app when the time ends." |
| Countdown over an app (since Amendment 7) | "1:59 left" in a small dark pill at the top centre of the screen, in the last 5 minutes of an app's time (`service/TimeLeftPill.kt`; 2 before Amendment 9). Since Amendment 8 that includes an app in split screen, a floating window or picture-in-picture; with several, it counts down for the one whose time ends first. The accessibility service draws it as an accessibility overlay: it takes no touches, so the app works under it, and it is always dark, whatever the theme |
| Device Admin explanation | "Krypt uses Device Admin to make itself harder to uninstall, so a determined user has to visit Settings first." |
| Share target label | "Open in Krypt" |
| Launcher icon | `res/drawable/ic_launcher_*`; free to redesign (adaptive icon, monochrome layer welcome) |

---

## 8. Shared components and patterns

### 8.1 Colours today (free to evolve; keep the meanings)

| Token | Value | Use today |
|---|---|---|
| `KryptPurple` | `#2E1A47` | Light primary; the lock-screen gradient centre |
| `KryptPurpleDark` | `#1A0A28` | Dark background; the gradient end |
| Gradient start | `#201033` | The lock-screen gradient top |
| `KryptAmber` | `#F3C98B` | Dark primary; the Ask Guardian button; lock-screen links |
| `KryptError` | `#B3261E` | Errors; the "not granted" cross |
| `PermissionGrantedGreen` | `#34C759` | The "granted" check; the unlock flash; (today, also the "Locked" label) |
| Light background | `#F7F4FA` | Light theme background |

**Meanings to keep, even if the hues change:**

- **Protected / locked:** calm and confident.
- **Unlocked for now / time left:** a positive accent.
- **Time's up / paused:** caution.
- **Error:** red.
- **Granted:** green.

### 8.2 Building blocks

| Component | Notes |
|---|---|
| **PIN entry** | Masked, numeric, a length limit, a spinner while checking, a lockout countdown. Used in 3 places: PIN setup (two fields), the PIN gate, the Guardian screen |
| **Lockout countdown** | "Try again in N s", switching to minutes past 60 s, ticking every second (`ui/common/PinLockout.kt`) |
| **Permission step** | Title, explanation, an action that opens Settings, an animated granted indicator |
| **Mandatory progress** | A bar plus "Required permissions: N of 2" |
| **App row** | Icon, name, status line, switch, and an optional inline action (Lock now / End daily time) |
| **Status line** | Text plus colour plus (suggested) an icon, so state is never colour alone |
| **Duration chips** | Single-choice chips with a "Set ..." chip that reveals a numeric field with validation |
| **Summary sentence** | "Allows: ..." restating the choice in words |
| **Share hand-off** | The button, then the system share sheet, then a status message about what happens next |
| **Paste link** | A text button that reads the clipboard; a snackbar or inline text when no link is found |
| **Full-screen block** | The lock screen layout: dark gradient, centred column, one primary action |
| **Toast** | Short confirmations and errors from the windowless screens |

### 8.3 Motion

- The permission indicator: cross-fade plus scale, 200-500 ms (required).
- The unlock flash: at least 500 ms (required).
- Everything else is free. Keep motion subtle, and honour the system's
  "remove animations" setting.

---

## 9. Key user journeys

1. **First setup.** Install Krypt, then:
   1. Onboarding: grant Accessibility and Overlay; Battery, Device Admin
      and Notifications, or Skip them; Finish.
   2. Hand the phone to the Guardian, who sets the PIN, then hand it back.
   3. The PIN gate, then Home.
2. **Lock existing apps.** PIN gate -> Home -> search "insta" -> switch on.
3. **A new app is installed.** The "New App Protected" notification appears,
   and the app shows locked in Home.
4. **Subject opens a locked app.**
   1. The lock screen appears.
   2. Ask Guardian -> share sheet -> WhatsApp -> "Request sent...".
   3. The Guardian replies.
   4. The Subject taps the approval link: flash, toast, the app opens.
5. **Guardian approves.**
   1. Tap the request link in a chat; the approval screen opens.
   2. Choose One time, 30 min; type the PIN; Verify & send.
   3. The share sheet opens; send it back in the same chat.
6. **Every-day time.**
   1. The Guardian picks Every day, 1 hour, 7 days.
   2. The Subject uses the app until the hour is gone.
   3. The lock screen says "Time's up for today"; Ask Guardian still works
      for extra one-time time.
   4. With a shorter period (Amendment 6), for example 15 minutes every
      hour, the lock screen says when more time comes, and closes by itself
      when the next period starts.
7. **The link isn't tappable in the messenger.** Copy the whole message,
   then:
   - on the Guardian's phone: open Krypt -> Paste link;
   - on the Subject's phone: on the lock screen -> Paste approval link.
8. **Wrong PIN.** Two wrong PINs show "n attempts left"; the third locks the
   screen for 30 s with a countdown.
9. **The Guardian ends access early.** PIN gate -> Home -> **Lock now** or
   **End daily time**.
10. **Protection switched off.** The "Krypt is off" notification appears at
    once -> Accessibility settings. Switching Krypt back on removes it.
11. **Krypt stopped by the phone (Amendment 10).** Krypt starts again by
    itself within about 15 minutes. If it can't, "Krypt has stopped"
    appears -> Accessibility settings, where turning Krypt Locker off and
    on brings it back.

---

## 10. Words and messages

### 10.1 Glossary: use these words

| Say | Not | Meaning |
|---|---|---|
| Guardian | admin, parent (unless the product chooses it) | The person who holds the PIN |
| Guardian PIN | password, code | The 4-6 digit PIN |
| Locked | blocked, restricted | Opening it asks the Guardian |
| Ask Guardian | request unlock | Send a request link |
| Approval / unlock request | token, URL | The links that travel through chats |
| One time / Every day | session, allowance | The two kinds of access |
| Unlocked, N min left | grant | A one-time unlock in progress |
| Daily time | quota | Every-day minutes (per day, or since Amendment 6 per period of 5 minutes to 24 hours) |

The Subject is never called "Subject" in the UI; it is the spec's word.
Speak to them as "you".

### 10.2 Durations

- "15 min" on chips; "30 minutes" and "1 hour" in sentences.
- "3 days".
- "until Sep 28", in the device's short date style.
- "until 14:35", in the device's 12- or 24-hour style.

### 10.3 Error messages the screens show

| Where | When | Message |
|---|---|---|
| PIN screens | Wrong PIN | Wrong PIN. n attempts left. |
| PIN screens | Lockout | Too many wrong PINs. Try again in N s. / ... in N min. |
| PIN setup | Too short / not digits / mismatch / save failed | PIN must be at least 4 digits. / PIN must contain digits only. / The two PINs don't match. / Could not save the PIN. Please try again. |
| Guardian screen | Expired / corrupt / unreadable request | This unlock request has expired. Ask your Subject to send a new one. / This unlock request appears corrupted. / Could not read this unlock request. |
| Approval (toast) | Unreadable | Could not read this approval. |
| Approval (toast) | No matching request (already used, or another phone) | This approval doesn't match a pending unlock request. |
| Approval (toast) | Request expired | Your original request expired before the approval arrived. |
| Approval (toast) | Fails verification (wrong PIN on the other side, tampering) | This approval failed cryptographic verification. |
| Approval (toast) | Phone not set up | This device isn't set up yet. Open Krypt first. |
| Approval (toast) | Every-day approval while automatic time is off | Turn on Set time automatically in Settings, then tap the approval again. |
| Lock screen | Clipboard has no approval | No approval link on the clipboard. Copy your Guardian's whole reply first. |
| Paste link | No link found | No Krypt link found. Copy the whole message that contains the link first. |

---

## 11. Data reference

### 11.1 Limits

| Thing | Limit |
|---|---|
| Guardian PIN at setup | 4-6 digits |
| PIN typed on the Guardian screen | Up to 8 digits (4 minimum) |
| One-time access | 1-1440 minutes; presets 15, 30, 60, 120; default 15 |
| Every-day access | 1-1440 minutes a day for 1-365 days; day presets 3, 7, 14, 30; default 7. Since Amendment 6, or 1 minute up to the period, for a period of 5 minutes to 24 hours |
| Request lifetime | 5 minutes from Ask Guardian |
| Unlock flash | At least 500 ms |
| Permission indicator animation | 200-500 ms |

### 11.2 Behaviour worth knowing

- One-time access ends on time, even with the app open. Since Amendment 7
  that holds even if the phone slept during it. It survives Krypt
  restarting, but not a reboot.
- Since Amendment 7, an app's last minutes (of an unlock, repeating time,
  or both) show a countdown over it, "1:59 left", and the app is blocked
  the moment its time ends. The countdown starts at 5:00 (at 2:00 before
  Amendment 9).
- Daily time counts only while the app is in front with the screen on, and
  it is saved every 30 s. Since Amendment 6 it is kept per minute of the
  clock, and a period adds up its minutes. Since Amendment 8 it counts
  while the app is anywhere on screen: in front, in split screen, in a
  floating window or in picture-in-picture, and several apps can count at
  once.
- Daily time **pauses** while "Set time automatically" is off.
- A new every-day approval replaces the old rule. Time already used today,
  or in the current period, still counts.
- One-time time never uses up daily time.

### 11.3 PIN lockout schedule

This is shared by the PIN gate and the Guardian screen on the same phone,
and it survives restarts.

| Wrong PIN | Result |
|---|---|
| 1st, 2nd | Message with attempts left |
| 3rd | Locked 30 s |
| 4th-5th | Locked 2 min |
| 6th-7th | Locked 10 min |
| 8th and later | Locked 1 hour |

A correct PIN resets the count.

---

## 12. Opportunities and non-goals

### 12.1 Possible with today's logic (UI-only)

- **A first-run choice for the Guardian's phone:** "I'm setting up a phone
  to protect" or "I'm a Guardian - I only approve requests". The second path
  needs only Paste link and an explanation; no permissions.
- **App names on the Guardian screen.** The request carries only the
  package name.
  - If the Guardian's phone has the same app installed, the UI could look up
    its name and icon locally.
  - Otherwise, show the package name with an explanation.
- **Home list:** sections, sorting, filter chips, a summary line, and
  confirm-before-unlocking.
- **Lock screen:** reason illustrations, a request countdown, and a calmer
  "sent" state that says what happens next.
- **OEM battery help** in onboarding, from `ui/main/OemBatteryLinks.kt`.
- **Notification tap.** "New App Protected" passes the package to Krypt
  (`NotificationHelper.EXTRA_PACKAGE`), but Home ignores it. After the PIN
  gate, the UI could scroll to and highlight that app.
- **A distinct empty state** when the device has no lockable apps, instead
  of "Loading apps..." forever.

### 12.2 Not possible without new logic: do not design as working features

- Changing, resetting or recovering the Guardian PIN; replacing the
  Guardian.
- A settings screen. The only stored settings are internal ones.
- Re-running onboarding later, or a permissions screen outside onboarding.
  Indicators are onboarding-only by spec.
- Usage statistics, history or reports.
- Schedules ("weekends only", "not after 9 pm").
- Several Guardians, or approvals that need more than one.
- Biometric unlock; QR or NFC pairing.
- Showing the Subject's or the Guardian's name. Neither is stored or sent.
- Bulk lock or unlock.
- Anything needing the internet: accounts, sync, cloud backup, remote
  approval.

---

## 13. Appendix: what each screen is wired to

The redesign replaces the Composables, and keeps these ViewModels and state
classes as they are.

| Screen | ViewModel | State it reads | Actions it calls |
|---|---|---|---|
| Route (MainActivity) | `ui/main/MainViewModel` | `onboardingComplete`, `masterKeyConfigured`, `appEntryUnlocked` | `markOnboardingComplete()`, `refreshMasterKeyConfigured()`, `markAppEntryUnlocked()`, `lockAppEntry()` (on stop) |
| Onboarding | `ui/onboarding/OnboardingViewModel` | `uiState: OnboardingUiState` (currentStep, stepIndex, totalSteps, permissions map, mandatoryGranted/Total, canFinish, canSkip, isFirstStep, isLastStep) | `next()`, `back()`, `skip()`, `tryFinish(onComplete)`; `permissionState.bind(lifecycleOwner)` |
| Set Guardian PIN | `ui/setup/PinSetupViewModel` | `state: PinSetupState` (Idle, Saving, Done, Error(TooShort / NotNumeric / Mismatch / SaveFailed)) | `save(pin, confirm)` (CharArrays), `acknowledgeError()` |
| PIN gate | `ui/main/AppEntryViewModel` | `state: AppEntryState` (Idle, Validating, Success, WrongPin(n), LockedOut(ms), NotConfigured) | `submitPin(pin)`, `onSuccessHandled()`, `acknowledgeError()` |
| Home | `ui/home/HomeViewModel` | `state: HomeUiState` (rows: packageName, displayName, isLocked, status: AppRowStatus; query); `iconCache` | `setQuery(q)`, `refresh()`, `onToggle(row)`, `onLockNow(row)`, `onEndDailyTime(row)` |
| Lock screen | `ui/lock/LockScreenViewModel` | `state: LockScreenState` (lockedPackage, request: RequestStatus, reason: LockReason); `shareRequests` (URLs to share); `closeRequests` | `show(pkg)`, `askGuardian()` |
| Guardian approval | `guardian/GuardianPinViewModel` | `state: GuardianPinState` (Loading, Ready(request, attemptsLeft, lockoutRetryInMs, errorMessage, form: AccessForm), Validating, Approved(request, approvalUrl, access), FatalError(key)) | `parseIncoming(url)`, `setKind()`, `pickPeriod()`, `editPeriod()`, `pickMinutes()`, `editMinutes()`, `pickDays()`, `editDays()`, `approve(pin)`, `acknowledgeError()`, `lockoutElapsed()` |
| Approval confirmation | (Activity with `ApprovalConsumer`, `UnlockSuccessEffect`) | `ApprovalOutcome` (OneTime / EveryDay) or `ApprovalError` | - |
| Paste / Open in Krypt | `ui/link/LinkRouting.kt` | - | `pasteKryptLinkAction(approvalsOnly, onNoLink)`, `openKryptLinkIn(text)`, `shareTextChooser(context, text, title)` |

**Link formats** (fixed; full detail in
`polaris-specs/001-krypt-app-locker/contracts/`):

```
krypt://request?v=1&req=<uuid>&app=<package>&salt=<b64url>&pinProof=<b64url>&kdfIter=<n>&iat=<s>&ttl=300&caps=daily,forgood,periods
krypt://approve?v=1&req=<uuid>&data=<b64url AES-GCM payload>&iat=<s>
```

---

## 14. What to deliver

1. **A design system:** colour tokens for light and dark (with the meanings
   in section 8.1), type, shape, spacing and motion, plus the components in
   section 8.2 with all their states. Use Material 3 components only.
2. **Every screen in section 7**, with every state listed there. Phone
   portrait, plus large-font and dark variants for the lock screen, the
   Guardian screen and Home.
3. **The lock screen's first frame:** the window background alone, matching
   the full screen.
4. **The journeys in section 9** as prototypes, including the share-sheet and
   Settings hand-offs.
5. **Updated text** for any string that changes, as `strings.xml` entries
   with plurals.
6. **The launcher icon** and notification small icon, if they change.

**For the developer who builds it.** The instrumented tests in
`app/src/androidTest/java/com/krypt/app/e2e/` find elements by visible text
and test tags. These include "Ask Guardian", "Verify & send approval",
"Paste link", "Paste approval link", "Finish", "Next", "Skip", "Unlock",
"Go to home screen", "This app is locked", "Lock now", "End daily time",
"Every day", "Set minutes", "Set days", "30 min", "Required permissions: 1
of 2", the content description "Permission granted", and the tags
`PIN_FIELD_TAG`, `MINUTES_FIELD_TAG`, `DAYS_FIELD_TAG`. Renaming any of
these means updating those tests in the same change.
