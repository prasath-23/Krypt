The short line under an app's name in Home, always an icon plus words, in the colour of its meaning.

| Status | Icon | Colour | Line |
|---|---|---|---|
| Not locked | — (no icon) | `on-surface-variant` | Not locked |
| Locked | `lock` | `locked` | Locked |
| Unlocked (one time) | `lock_open` | `open` | Open · 12 min left (ticking) |
| Daily, time left | `lock_clock` | `open` | Daily · 25 of 60 min left · until Sep 28 |
| Daily, used up | `hourglass_bottom` | `caution` | Daily · done for today · until Sep 28 |
| Daily, paused | `schedule` | `caution` | Daily · paused until automatic time is on |
| Shorter period, time left | `lock_clock` | `open` | Every 1 h 30 min · 10 of 15 min left · until Sep 28 (every hour: Hourly · 10 of 15 min left · until Sep 28) |
| Shorter period, used up | `hourglass_bottom` | `caution` | Every 30 min · back at 14:30 · until Sep 28 |
| Shorter period, used up in the rule's last period | `hourglass_bottom` | `caution` | Every 2 h · done · until Sep 28 |
| Shorter period, paused | `schedule` | `caution` | Every 2 h · paused until automatic time is on |

**Badges** (`kx-badge`, fully round) carry the counts in Home's header: "42 locked" on `locked-container`, "2 open now" on `open-container`.

**Rules**
- `lock_open` means only an active one-time unlock; never put it on a Not locked row. Not locked carries text alone so every row keeps the same height.
- Use the middle dot " · " as the only separator. Numbers that tick are set in `mono-time` so they don't jitter, and so is the "back at" time.
- Repeating time names its period first: "Daily" for a day, "Hourly" for an hour, "Every 30 min" under an hour, "Every 2 h" for whole hours, and "Every 1 h 30 min" otherwise. The "back at" time is on the phone's own clock, in its current zone, and follows its 12- or 24-hour style ("back at 4:00 PM").
- Wrap to a second line at large font scale; never cut off the time left.
