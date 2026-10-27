The countdown over an app in its last 2 minutes: a small dark pill at the top of the screen that says how long is left, "1:59 left". The app works under it as before.

**Shows:** a pill at the top centre of the screen, 8dp below the status bar, with fully round ends (`radius-full`). A 16dp icon (`icon-sm`), then the time in Geist Mono and "left" in Instrument Sans 500, both at 15sp. The pill is `#2E2E2E` with `#F2F2F2` text (the light theme's `inverse-surface` and `inverse-on-surface`), and a 1dp white hairline at 20% lifts it off dark apps.

| State | When | Icon | Line |
|---|---|---|---|
| Time left | The last 2 minutes | `timer` in the dark theme's `open` (`#7CCFB8`) | 1:59 left |
| Last 30 s | The last 30 seconds | `hourglass_bottom` in the dark theme's `caution` (`#F5A06A`) | 0:28 left |

The time is minutes and seconds, rounded up, so the last second shows 0:01 and 0:00 never shows while the app is open. It ticks every second.

**When it shows:** only while a locked app with time is on screen (in front, in split screen, in a floating window or in picture-in-picture), and the screen is on and unlocked. With several such apps, it counts down for the one whose time ends first. It goes away when the app leaves the screen, when the screen goes off, when the app is blocked, or when more time arrives, and it comes back with the app. When a period ends first and the next one starts full, it counts on across the end: with 1 minute every 5 minutes, at 08:04:30 it shows 1:30, and the app is blocked at 08:06.

**The consumer provides** the time left, in milliseconds. `TimeLeftWatcher` calls `TimeLeftDisplay.show(pkg, msLeft)` and `hide()`, and `TimeLeftPill` draws it. The text is `time_left_pill` ("%1$s left") around `timeLeftText(msLeft)`.

**Rules**
- Always dark, like a toast, so it reads the same over any app, light or dark. It doesn't follow the Appearance setting.
- It never blocks. When the time ends, Krypt's lock screen replaces the app, and the pill goes with it.
- It is an accessibility overlay (`TYPE_ACCESSIBILITY_OVERLAY`), drawn by the accessibility service: it needs no other permission, takes no focus, and lets touches pass through to the app.
- One line, with no button and no full stop.
- Its window title is "KryptTimeLeft", which the e2e tests find with `dumpsys window windows`. The service ignores the window event Android sends for it, by its accessibility class name, so it never counts as an app coming to the front, and repeating time goes on being counted under it.
