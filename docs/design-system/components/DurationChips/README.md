How the Guardian chooses what to allow: a segmented control for how often, and chip rows for how long and how many days.

- **How often** (only when the request says the phone supports more than one time): **Once** / **Daily** / **For good**, a Material 3 `SingleChoiceSegmentedButtonRow`, with only the options the phone supports (`caps=daily`, `caps=forgood`). The selected segment is filled with `primary`.
- **For good** removes the app's lock on the child's phone, as Home's "Remove lock" does. It has no duration, so the rows below are hidden while it is selected.
- **For how long:** chips for 15 min, 30 min, 1 hour, 2 hours and **Custom**. Custom reveals a minutes field (1-1440, `MINUTES_FIELD_TAG`), with the error "Choose 1 to 1440 minutes."
- **For how many days** (Daily only): chips for 3, 7, 14, 30 days and **Custom**. Custom reveals a days field (1-365, `DAYS_FIELD_TAG`), with the error "Choose 1 to 365 days." A `caution-container` note sits under this row: "Today counts as day 1, including time already used. Need more time today? Choose Once."

**Chips** are 36dp tall with a hairline `outline` border at `radius-sm`; the selected chip fills with `primary`. There are no checkmarks: the fill is the selection, and TalkBack reads "selected".

**The consumer provides** the `AccessForm` and calls `setKind()`, `pickMinutes()`, `editMinutes()`, `pickDays()`, `editDays()`.

**Rules**
- Defaults: Once, 15 min. Daily defaults to 7 days.
- An invalid custom value disables Continue on step 1 of the Guardian flow.
- The choice is kept through wrong PINs and lockouts.
