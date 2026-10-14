The short line under an app's name in Home, always an icon plus words, in the colour of its meaning.

| Status | Icon | Colour | Line |
|---|---|---|---|
| Not locked | — | — | (none) |
| Locked | `lock` | `locked` | Locked |
| Unlocked (one time) | `lock_open` | `open` | Open · 12 min left (ticking) |
| Daily, time left | `lock_clock` | `open` | Daily · 25 of 60 min left · until Sep 28 |
| Daily, used up | `hourglass_bottom` | `caution` | Daily · done for today · until Sep 28 |
| Daily, paused | `schedule` | `caution` | Daily · paused until automatic time is on |

**Badges** (`kx-badge`, fully round) carry the counts in Home's header: "42 locked" on `locked-container`, "2 open now" on `open-container`.

**Rules**
- Use the middle dot " · " as the only separator. Numbers that tick are set in `mono-time` so they don't jitter.
- Wrap to a second line at large font scale; never cut off the time left.
