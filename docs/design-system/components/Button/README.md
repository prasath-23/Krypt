Three button weights and one inline size, all with soft `radius-md` corners: there are no pill buttons in Krypt.

- **Primary** (`primary` fill, `on-primary` text, `button-height`, full width on screens): the one action a screen exists for. Request access, Continue, Approve and send, Start protecting. Never two on one screen.
- **Secondary** (1dp `outline` border): a real alternative that leaves Krypt, like "Open settings", with a trailing `open_in_new` icon so the hand-off is announced before it happens.
- **Tertiary** (text only, 48dp target): Paste reply, Back to home, Not now, Keep it locked.
- **Small** (`button-height-sm`, `radius-sm`, hairline): actions inside a list row, such as Relock and Stop.

**The consumer provides** the label (a string resource), an optional leading or trailing `icon-sm` icon, `enabled`, and a busy flag. Busy shows a 16dp spinner before a new label ("Checking PIN"), and the button ignores taps while busy.

**Rules**
- Labels are verbs in sentence case, at most three words.
- Never fix a button's height; it grows with font scale.
- Compose: `Button` / `OutlinedButton` / `TextButton` with `shape = RoundedCornerShape(14.dp)`, `contentPadding` 20dp horizontal and `Modifier.heightIn(min = 52.dp)`.
