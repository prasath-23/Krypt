# Icons

Material Icons, **Rounded** (`Icons.Rounded.*`, from `material-icons-extended`), copied verbatim from google/material-design-icons (Apache 2.0). Single-ink 24dp glyphs: in a page `<img>` they stay black, so in Compose tint them with the token named here.

| Icon | Means | Tint |
|---|---|---|
| `lock` | Locked; the lock-screen reason | `locked`, `on-surface` |
| `lock_open` | Open now | `open` |
| `lock_clock` | Repeating time left (daily, or in a shorter period) | `open` |
| `hourglass_bottom` | Done for today; done for now; the time-left pill's last 30 seconds | `caution`; on the pill, the dark theme's `caution` |
| `schedule` | Repeating time paused | `caution` |
| `timer` | Request expiry; time left, on the time-left pill | `on-surface-variant`; on the pill, the dark theme's `open` |
| `check_circle` | Allowed; the approval check | `granted`, `on-flash` |
| `cancel` | Not allowed yet | `on-surface-variant` |
| `error` | Inline error | `error` |
| `share` | Request access, Approve and send (hand-off to the share sheet) | on the button |
| `content_paste` | Paste reply | `on-surface` |
| `link` | Paste a link | `on-surface` |
| `home` | Back to home | `on-surface` |
| `open_in_new` | Leaves Krypt for Settings | on the button |
| `backspace` | Keypad delete | `on-surface-variant` |
| `search` | Search apps | `on-surface-variant` |
| `chevron_right` | Row opens something | `on-surface-variant` |
| `arrow_forward` | First-run choice rows | `on-surface-variant` |
| `shield` | Not used: the notification small icon is the brand mark, `krypt-mark-sm.svg` (see Logos) | — |
| `accessibility_new`, `layers`, `battery_alert`, `admin_panel_settings`, `notifications` | The five onboarding items | `on-secondary-container` |
| `info` | Explanations | `on-surface-variant` |
| `close` | Dismiss a sheet | `on-surface` |
| `contrast` | Appearance (Home header) | `on-surface` |
| `light_mode`, `dark_mode`, `settings_brightness` | Reserved for appearance hints | `on-surface-variant` |

Every icon that carries meaning has a TalkBack label from `strings.xml`; one beside its own word uses `null`.
