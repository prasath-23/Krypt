How the Guardian chooses what to allow: a segmented control for how often, and chip rows for how often time comes back, how long and how many days.

- **How often** (only when the request says the phone supports more than one time): **Once** / **Repeating** / **For good**, a Material 3 `SingleChoiceSegmentedButtonRow`, with only the options the phone supports (`caps=daily`, `caps=forgood`). The selected segment is filled with `primary`.
- **Repeating** is time that comes back every day, or in a shorter period such as every 30 minutes or every 2 hours. Before Amendment 6 it was **Daily**.
- **Every** (Repeating only, and only when the request carries `caps=periods`): chips for 30 min, 1 hour, 2 hours, 4 hours, 6 hours, 12 hours and **Day**, then **Custom**. Day is the default. Custom reveals an "Hours" field (`PERIOD_FIELD_TAG`, suffix "hours", decimal keypad) that takes any period from 5 minutes to 24 hours: "1.5", "1,5" and "1:30" are 90 minutes, "0.5" is 30, "5" is 5 hours, rounded to whole minutes. Anything else shows "Choose 5 minutes to 24 hours, like 1.5 or 1:30." Without `caps=periods` the row isn't shown, and Repeating means a day.
- Periods follow the clock from midnight: every 2 hours is 00:00-02:00, 02:00-04:00, and so on. When the period doesn't divide a day, the day's last period is shorter and ends at midnight: every 5 hours ends with 20:00-24:00.
- **For good** removes the app's lock on the child's phone, as Home's "Remove lock" does. It has no duration, so the rows below are hidden while it is selected.
- **For how long:** chips for 15 min, 30 min, 1 hour, 2 hours and **Custom**. When repeating, only chips shorter than the period are shown, so the time is still a limit. A period of an hour or less uses 5, 10, 15 and 30 min instead: every 30 minutes shows 5, 10 and 15 min, every hour all four. Every 90 minutes shows 15 min, 30 min and 1 hour. A 5-minute period has no shorter chip, so only Custom is shown, and the minutes switch to Custom for the Guardian to type. If the selected chip is no longer shown, the largest one that fits is selected instead. Custom reveals a minutes field (`MINUTES_FIELD_TAG`) that takes 1 to 1440 minutes, or 1 up to the period when repeating, with the error "Choose 1 to N minutes." ("Choose 1 to 90 minutes." every 90 minutes).
- **For how many days** (Repeating only): chips for 3, 7, 14, 30 days and **Custom**. Custom reveals a days field (1-365, `DAYS_FIELD_TAG`), with the error "Choose 1 to 365 days." A `caution-container` note sits under this row:
  - every day: "Today counts as day 1, including time already used. Need more time today? Choose Once."
  - shorter periods: "Periods follow the clock from midnight: 00:00, 01:30, 03:00 and so on. Time already used in this one counts. Need more right now? Choose Once."
  - periods that don't divide a day add a sentence: "Periods follow the clock from midnight: 00:00, 05:00, 10:00 and so on. The last one ends at midnight. Time already used in this one counts. Need more right now? Choose Once."
  - Both list the first three start times, in the phone's 12- or 24-hour style, then "and so on". With three starts or fewer they list them all: "00:00, 08:00 and 16:00".

**Chips** are 36dp tall with a hairline `outline` border at `radius-sm`; the selected chip fills with `primary`. There are no checkmarks: the fill is the selection, and TalkBack reads "selected".

**The consumer provides** the `AccessForm` and calls `setKind()`, `pickPeriod()`, `editPeriod()`, `pickMinutes()`, `editMinutes()`, `pickDays()`, `editDays()`.

**Rules**
- Defaults: Once, 15 min. Repeating defaults to every Day, for 7 days.
- An invalid custom value disables Continue on step 1 of the Guardian flow.
- The choice is kept through wrong PINs and lockouts.
