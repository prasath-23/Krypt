One installed app in Home. Every row has the same shape and height: icon, name, one status line, switch.

**Shows:** the app icon at `app-icon-row` masked to `radius-sm`, the name in `title-medium`, one StatusLine (Not locked shows "Not locked" in `on-surface-variant` with no icon, so no row is shorter and `lock_open` keeps one meaning), and the Material 3 Switch at the end. There are no buttons inside rows; rows are 68dp tall and separated only by hairlines.

**Ending access early:** a row in Open now is tappable. It opens a small bottom sheet with the app, its status ("Open · 12 min left · until 14:35") and one primary action, **Relock now**, or **Stop daily time** for daily rows, plus **Keep it open**.

**The screen around it:** an eyebrow ("KRYPT" with the mark), the serif title "Your apps", the two count badges, a search field, then sections in this order: **Open now**, **Locked**, **Not locked**. Each section has a small capitals header with its count. When the user arrives from a "New app protected" notification, that row is highlighted on `primary-container` for 2 seconds.

**The consumer provides** a `HomeUiState` row (`displayName`, `isLocked`, `status`), its icon from `iconCache`, and `onToggle`, `onLockNow` (Relock now), `onEndDailyTime` (Stop daily time).

**The switch** is Material 3's 52×32 Switch in Krypt colours: off is a 1.5dp `outline` ring with a small `outline` thumb; on is a `primary` track with an `on-primary` thumb. On means locked, and it stays on while an unlock or daily time is running.

**Rules**
- Turning a lock **on** happens at once. Turning it **off** opens a sheet first: "Unlock Notes for good?", with **Remove lock** and **Keep it locked**.
- The switch and the tappable row each have a 48dp target and their own TalkBack label ("Lock Instagram"; "Instagram, open, 12 minutes left. Tap for options").
- Other states: "Finding your apps…" with a thin progress line, `No apps match “abc”.`, and an empty state when no app can be locked.
