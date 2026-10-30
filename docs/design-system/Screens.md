# Screens

How each screen in the brief is built from this system. Every screen is a thin Composable over its existing ViewModel; only the presentation is new.

## First run: who is this phone for?

A new opening step, before any permission (it needs no new logic). Eyebrow "KRYPT" with the mark, the `display-small` title "Welcome to Krypt", and two large choice rows in one group:
- **"I'm protecting this phone"** continues to setup.
- **"I'm a Guardian"** shows one screen: "You only need Krypt to approve requests. When a request arrives in a chat, tap its link." with **Paste a link** as its primary action and no permissions asked.

## Onboarding: one checklist

Title "Before Krypt can protect", the two-tick MandatoryProgress, then the PermissionStep checklist in two groups, Required and Optional. Each row opens its Settings page and shows Allowed / Not allowed yet live (an optional row says Not allowed). **Start protecting** is pinned at the bottom and disabled until 2 of 2, with **Paste a link** as a tertiary in the top bar.

## Set Guardian PIN: a hand-over in three beats

1. **"Give this phone to your Guardian."** The display title, one line ("They'll choose a PIN only they know"), and **Continue**.
2. **PIN, then confirm:** eyebrow "STEP 1 OF 2" / "STEP 2 OF 2", the title "Choose a PIN" / "Once more", PinEntry (4-6), and **Save PIN**. While saving, the dots breathe and the line reads "Securing your PIN…".
3. **"Done. Hand the phone back."** with `check_circle` in `granted`; the app then moves to the gate.

Errors appear on the message line: "Use at least 4 digits.", "Numbers only.", "Those PINs don't match.", "Couldn't save the PIN. Try again."

## Krypt Admin gate

Eyebrow "KRYPT ADMIN" and the title "Guardian only". PinEntry with the line "Enter the Guardian PIN to manage apps", then **Continue**. A lockout replaces the line with LockoutCountdown. **Paste a link** is a tertiary below, with its one-line explanation.

## Home: your apps

See AppRow: eyebrow, serif title "Your apps", count badges, search, then the sections Open now / Locked / Not locked. Every row is the same height with no buttons inside; tapping a row with an unlock or repeating time opens a sheet with Relock now, Stop daily time or Stop repeating time. Removing a lock asks in a sheet. A `contrast` icon at the top right opens the Appearance sheet (System default / Light / Dark).

## Lock screen

See LockScreen: the eyebrow, the app tile, the one-word serif statement and its line, the ShareHandoff timeline once sent, and a state-driven bottom action bar (Request access while idle; Paste reply with Send again while waiting). It follows the Appearance setting (System default unless changed).

## Guardian approval (on the Guardian's phone)

Two steps, so the keypad is never inside a scrolling page (see GuardianApproval).
- **Step 1 · Choose:** eyebrow "ACCESS REQUEST", the title "Instagram" (or the package in mono, with an `info` tertiary "Why only a package name?"), the age line "Sent 1 min ago · expires in 4:00", DurationChips, the live SummarySentence, and a pinned primary **Continue**.
- **Step 2 · Confirm:** a bottom sheet with the SummarySentence fixed at the top, PinEntry (4-8), and **Approve and send**. Nothing in the sheet scrolls. Dismissing it returns to step 1 with the choice kept and the PIN cleared.
- **States:**
  - Loading: thin progress line.
  - Fatal: `error-container` panel with one sentence ("This request expired. Ask for a new one." / "This request looks damaged." / "Couldn't read this request.").
  - Wrong PIN and lockout: inside the sheet, via PinEntry and LockoutCountdown.
  - Checking: breathing dots.
  - Approved: `display-large` "Sent." and "You can close this screen." after the share sheet.
  - Unknown link: "Not a Krypt request".
- Screenshots are blocked on the whole activity, sheet included.

## Approval confirmation

No layout: UnlockFlash, then the toast.

## System surfaces

- **Notification small icon:** `krypt-mark-sm.svg`, the small brand mark as a white-on-transparent vector drawable (Android tints it). A generic `shield` would read as antivirus. The texts are "New app protected", "Instagram (com.instagram.android) is locked by default.", "Krypt is off" and "Locked apps can open freely. Tap to turn Krypt back on in Accessibility settings.", and since Amendment 10 "Krypt has stopped" and "Locked apps can open freely. Tap, then turn Krypt Locker off and on again to restart it."
- **Share sheet titles:** "Send your request" and "Send your reply".
- **Time-left pill:** in the last 5 minutes of an app's time, "1:59 left" in a small dark pill at the top centre of the screen, over whatever is in front. The app may also be in a small window, such as picture-in-picture. Krypt's accessibility service draws it; it takes no touches, so the app under it works as before. See TimeLeftPill.
