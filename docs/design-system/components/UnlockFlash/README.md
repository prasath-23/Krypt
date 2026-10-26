The approval confirmation: a soft mint field with a ringed check, a short vibration and a toast, then the app opens. There are no buttons and no fields.

**Shows:** `flash` covers the translucent window at 94% opacity. A 96dp `on-flash` ring holds a `check_circle`; the ring settles from 70% to full size. The whole moment lasts `motion-flash` (700ms; the spec requires at least 500ms). Alongside it: a 50ms vibration and the TalkBack announcement "Opened by your Guardian". The toast follows.

**Outcomes**
- **Once:** flash, then "Instagram is open until 14:35".
- **Repeating, with time left in this period:** flash, then "Instagram: 1 hour a day until Sep 28", "Instagram: 15 minutes an hour until Sep 28" or "Instagram: 30 minutes every 2 hours until Sep 28".
- **Repeating, this period already used up:** the toast only, with no flash: "Instagram: daily time is set, but today's is used up", or "Instagram: repeating time is set, but it's used up for now" for a period shorter than a day.
- **For good:** flash, then "Instagram is unlocked for good".
- **Error:** one toast, then the window closes.

**The consumer provides** the `ApprovalOutcome` from `ApprovalConsumer`, played by `UnlockSuccessEffect`.

**Rules**
- `flash` is used nowhere else and never sits under text.
- With animations removed, show the field without motion for 500ms.
