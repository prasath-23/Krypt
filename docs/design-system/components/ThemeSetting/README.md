The Appearance setting: three choices, System default, Light and Dark, with System default preselected.

**Shows:** a contrast icon button (`contrast`, label "Appearance") at the top right of Home's header opens a bottom sheet titled "Appearance" with three option cards side by side. Each card has a miniature preview (System default is split half light, half dark), a name and one line: "Matches your phone", "Always light", "Always dark". The selected card has a 2dp `primary` ring; the others a hairline.

**Behaviour**
- **System default** (the default on install) follows the phone's dark-mode setting live, through `isSystemInDarkTheme()`.
- **Light** / **Dark** force that theme for every Krypt screen, including the lock screen, PIN screens, onboarding and the Guardian approval screen.
- The change applies immediately, with no restart. The lock screen's window background must follow the same choice (set the activity's night mode with `AppCompatDelegate.setDefaultNightMode`) so its first frame still matches.

**Needs new logic:** the brief has no stored user setting today. This needs one small preference (for example `appearance = SYSTEM | LIGHT | DARK` in `SettingsRepository`), read by `KryptTheme` and by the night-mode call above. It sits behind the Guardian PIN because Home does.

**Rules**
- Three options only, always in this order. No "automatic by time of day".
- Each card is a `radio` with its name as the TalkBack label, and states "selected".
