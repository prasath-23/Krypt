Onboarding is one checklist: each permission is a row with its icon, a plain-words title, its live state and, until it is allowed, a button that opens the right Settings page.

**Rows** (in grouped `surface-container-low` sections at `radius-lg`, divided by hairlines):
- **Required:**
  - See when a locked app opens: Accessibility (`accessibility_new`).
  - Show the lock over apps: display over other apps (`layers`).
- **Optional:**
  - Keep running in the background: battery optimisation (`battery_alert`).
  - Make Krypt harder to remove: Device Admin (`admin_panel_settings`).
  - Alerts about new apps: notifications (`notifications`).

**The consumer provides** `OnboardingUiState.permissions` and each step's settings intent. Tapping a row's button sends the user to that Settings page.

**State:** "Allowed" with `check_circle` in `granted`, or "Not allowed yet" (a required row) / "Not allowed" (an optional row) with `cancel` in `on-surface-variant`. It is refreshed on resume and changes with a fade + scale over `motion-indicator` (200-500ms). Its TalkBack label is the same word.

**Rules**
- The button reads "Allow in settings" with an `open_in_new` icon, which says before the hand-off that Krypt is leaving.
- The battery row adds a "Help for Xiaomi, Samsung and others" tertiary button that opens the OEM pages in `OemBatteryLinks.kt`.
- These indicators appear only in onboarding.
- Optional rows never block anything and have no Skip; leaving one alone is the skip.
