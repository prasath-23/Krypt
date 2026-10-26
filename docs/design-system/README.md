Krypt locks apps on a phone so that only a trusted second person, the Guardian, can open them, through links shared in any messenger, fully offline. This is its design language: **quiet, ink-first and exact**. One brand colour that is barely a colour, a serif voice for the few things that matter, hairlines instead of boxes, and space instead of decoration. It is built for Jetpack Compose with Material 3 and Material Icons, with every asset bundled in the APK.

The person behind a lock may be a child, someone in recovery or someone who chose to focus. Every screen is **calm, dignified and firm**: never punitive, never alarming, and never hinting at a way around the lock.

## Principles

- **One thing per screen.** Each screen has one serif statement and one primary action. Everything else is quieter.
- **Neutral, like the phone.** Pure white and Android's #121212 are the backgrounds, so Krypt blends into the system and the apps around it; near-black and near-white ink carry the brand. Colour appears only when it means something: teal for open, burnt orange for time's up, red for errors, green for allowed.
- **Lines, not boxes.** Group with 1dp `outline-variant` hairlines and `surface-container-low` sections. No shadows except on sheets.
- **Left-aligned and generous.** Text starts at the 20dp gutter (`space-5`). Sections are `space-8` apart. Nothing is centred except the PIN dots, the keypad and the countdown.
- **Say what happens next.** Every hand-off (share sheet, Settings, opening the app) is announced in words before it happens.

## Voice

- Short, warm and adult. Speak to the person as "you", and to the PIN holder as "your Guardian".
- **Serif statements end with a full stop:** "Locked.", "Done for today.", "Paused.", "Approved." They are the only full stops in titles.
- Buttons are sentence-case verbs of at most three words: "Request access", "Paste reply", "Approve and send".
- Use " · " as the only separator in status lines: "Open · 12 min left".
- No exclamation marks, no emoji, no blame. Errors say what to do: "That PIN isn't right. 2 tries left."
- Every string, label and content description lives in `strings.xml`, with plurals for counts.

**Words**

| Use | For |
|---|---|
| Guardian | The person who holds the PIN |
| Request access | Sending an unlock request |
| Reply | The approval message the Guardian sends back |
| Once / Daily | The two kinds of access |
| Open · N min left | A one-time unlock in progress |
| Daily · N of M min left | Daily time in progress |
| Relock now / Stop daily time | Ending access early (in the sheet opened from an Open now row) |

## Colour: Soft neutral

Backgrounds are `#ffffff` in light and `#121212` in dark (Android's own dark surface), with no tint. Near-black (`#1f1f1f`) is `primary` in light; near-white (`#e8e8e8`) is `primary` in dark. **Every screen, the lock screen included, follows the Appearance setting:** System default (the default, matching the phone), Light or Dark (see ThemeSetting). Map the Material roles one to one onto `lightColorScheme()` / `darkColorScheme()`. The Krypt roles (`locked`, `open`, `caution`, `granted`, `flash`, `on-flash`) live in a `KryptColors` CompositionLocal beside it.

- Text is `on-surface`. Supporting copy, eyebrows, hints and package names are `on-surface-variant`. Both read at 4.5:1 or better on `background` and every `surface-container-*` in both themes.
- Use one `primary` fill per screen: the primary button, plus the things that show "on" (the switch track, filled PIN dots, the selected segment or chip).
- **Meanings** (always with an icon and words, never colour alone):

| Meaning | Colour | Badge | Icon |
|---|---|---|---|
| Locked | `locked` (the ink itself) | `locked-container` | `lock` |
| Open now / daily time left | `open` | `open-container` | `lock_open`, `lock_clock` |
| Done for today / paused | `caution` | `caution-container` | `hourglass_bottom`, `schedule` |
| Error | `error` | `error-container` | `error` |
| Allowed (onboarding) | `granted` | — | `check_circle` |

- `flash` (mint `#3ccb8c`) is used only for the approval moment, with `on-flash` drawn on it. It is never used for status or under text.
- Focus is a solid 2dp `focus-ring` drawn 2dp outside the control.

## Type

Three bundled families (all SIL OFL, in `fonts/`; put them in `res/font/` and never load fonts at run time):

- **Instrument Serif** for statements and titles: `display-large` (48/52) for the one word on a full screen, `display-small` (34/40) for screen titles, `headline` (26/32) for sheets and the summary sentence. Always regular weight; use the italic only for the duration inside the summary sentence.
- **Instrument Sans** (400-700) for everything you read or tap: `title-large`, `title-medium` for app names, `body-large` / `body-medium` / `body-small`, `label-large` for buttons, `label-medium` for chips and badges, and `eyebrow` for the small capitals line above a title (write those strings in capitals).
- **Geist Mono** for numbers that tick or must be exact: `mono-package`, `mono-time` (4:59), and `mono-countdown` inside the lockout ring. The `keypad` digits use the sans.

**Font scale up to 200%:** never fix a height on anything holding text. Serif statements wrap; full screens scroll with their action area pinned to the bottom.

## Space, shape, size

- 4dp grid. `space-5` (20dp) gutter at every width from `screen-min` (320dp). `space-6` between a title block and its content; `space-8` between sections; `space-12` above the first eyebrow on full screens.
- Corners are soft, not round: `radius-sm` (10) for app icons, keys and chips; `radius-md` (14) for buttons, fields and segments; `radius-lg` (22) for grouped sections and the lock-screen app tile; `radius-xl` (28) for sheets. Only dots, the switch and badges use `radius-full`.
- Every control has a `touch-target` of at least 48dp. Primary and secondary buttons are `button-height` (52dp); small actions inside grouped cards (never inside Home rows) are `button-height-sm` (36dp).
- Icons are `icon-md` (20dp) in rows and buttons, `icon-sm` (16dp) in status lines and badges, `icon-lg` (24dp) for the lock reason.

## Motion

- Quiet and quick: `motion-fast` (120ms) for presses, selection and the switch; `motion-base` (220ms) for revealing a field or changing a status. All transitions use `motion-easing`.
- Onboarding's Allowed / Not allowed state fades and scales over `motion-indicator` (280ms; the spec allows 200-500).
- A wrong PIN shakes the dots once (280ms). While the PIN is checked, the filled dots breathe.
- The approval flash runs for `motion-flash` (700ms; the spec requires at least 500).
- When the system removes animations, everything is instant except the flash, which shows without motion for 500ms.

## Iconography and mark

**Material Icons, Rounded** (`Icons.Rounded.*`) only, in `on-surface` or the colour of their meaning. The exact glyphs are in `assets/Icons`. An icon beside its own word is decorative (`contentDescription = null`); every other icon gets a label. There are no illustrations and no emoji.

The **mark** is a keyhole with a cat's ears, and a smaller keyhole set into its leg (`assets/Logos`). At a glance it is a keyhole, the one way in; look again and a cat is keeping watch over a lock of its own, as cats guarded crypts in old legend. It is one flat silhouette in `on-surface`, or near-white on a `#121212` tile. Never outline it, add a face or whiskers, or put it in a circle. Set "Krypt" beside it in Instrument Serif; at 20dp and below use the optical small mark (`krypt-mark-sm.svg`, no inner keyhole); the eyebrow "KRYPT" uses it.

## Rules from the spec

These stay true whatever the design does.

- **No PIN entry on the locked phone's unlock path.** The lock screen offers only Request access (Send again while waiting), Paste reply and Back to home. Back goes to the launcher, never into the app.
- **PINs are digits only and always masked** (dots, never digits). They are never kept in saved state and are cleared after use. Lengths: 4-6 at setup, up to 6 on the Krypt Admin gate, 4-8 on the Guardian screen.
- **PIN checks take about 0.3s on purpose:** always show the checking state.
- **Lockouts:** the 3rd wrong PIN locks for 30s, then 2 min, 10 min, 1 hour. A live countdown shows meanwhile, and input is disabled.
- **Approval confirmation:** the flash (at least 500ms), a vibration and a toast naming the app. No buttons, no fields.
- **Onboarding:** permission states appear only there. Progress counts only the 2 required permissions, and Start protecting stays disabled until both are allowed.
- **The Home switch shows the lock setting:** on = locked. It stays on while an unlock or daily time is running.
- **The Guardian screen blocks screenshots** (`FLAG_SECURE`).
- **The lock screen's first frame** is its window background, which must equal `background` in the current theme so nothing flashes.

## Copy changes and the e2e tests

The instrumented tests in `app/src/androidTest/.../e2e/` find elements by these texts. Update them in the same change as the new strings. The tags `PIN_FIELD_TAG`, `MINUTES_FIELD_TAG` and `DAYS_FIELD_TAG` are unchanged.

| Old text | New text |
|---|---|
| Ask Guardian | Request access (Send again while a request is waiting) |
| Paste approval link | Paste reply |
| Go to home screen | Back to home |
| This app is locked | Locked. |
| Verify & send approval | Continue (step 1), then Approve and send (PIN sheet) |
| Paste link | Paste a link |
| Unlock (PIN gate) | Continue |
| Lock now | Relock now (in the row's sheet) |
| End daily time | Stop daily time (in the row's sheet) |
| One time / Every day | Once / Daily |
| Set minutes / Set days | Custom |
| Next / Skip / Finish | (removed: checklist) / (removed) / Start protecting |
| Required permissions: 2 of 3 | 1 of 2 required / 2 of 2 required · ready |
| Permission granted (description) | Allowed |
| 30 min | 30 min (unchanged) |

## Not synced

This design is built from scratch to `docs/UI-DESIGN-BRIEF.md` at `main@c5efda2`. Nothing visual comes from the test UI: not its colours, type, shapes, layouts, icon or wording. Only the brief's fixed rules, the Material 3 + Material Icons constraint and the screens' data carry over. The components are design references in HTML, not Compose code. To ship it, `ui/theme/` (`Color.kt`, `Type.kt`, `Shape.kt`), the lock screen's window background and the launcher icon all need to change.
