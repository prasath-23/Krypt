The Guardian's approval in two steps, so the keypad is never inside a scrolling page: choose the access, then confirm with the PIN in a sheet.

**Step 1 · Choose** (a normal scrolling screen, no keypad): eyebrow "ACCESS REQUEST", the app as the `display-small` title (or its package in mono with "Why only a package name?"), the age line "Sent 1 min ago · expires in 4:00", DurationChips, and the live SummarySentence. A pinned primary **Continue** opens step 2; it is disabled while a custom value is invalid.

**Step 2 · Confirm** (a modal bottom sheet over step 1): the SummarySentence fixed at the top, PinEntry (4-8 digits, dots + keypad), and the pinned primary **Approve and send**. Nothing in the sheet scrolls; it fits a 320×640dp screen. Dismissing the sheet (drag down or back) returns to step 1 with the choice kept and the PIN cleared.

**States in the sheet:** typing; checking (dots breathe, "Checking…"); wrong PIN (dots shake and clear, "That PIN isn't right. 2 tries left."); lockout (LockoutCountdown replaces the keypad; the sheet stays open). After success the share sheet opens and the screen shows `display-large` "Sent." with "You can close this screen."

**The consumer provides** `GuardianPinState` and calls `setKind()` / `pickMinutes()` / `editMinutes()` / `pickDays()` / `editDays()` on step 1 and `approve(pin)` on step 2. No ViewModel change: step 2 is presentation only.

**Live behaviour (as in the card):** changing Once / Daily / For good, a duration chip, the days or a custom value updates the SummarySentence on both steps at once; Daily reveals the days chips; For good hides the duration rows ("Instagram, unlocked for good."); an invalid custom value shows "Choose 1 to 1440 minutes.", hides the sentence and disables Continue. In the sheet the keypad fills the 8 dots, and Approve and send enables at 4 digits.

**Rules**
- `FLAG_SECURE` covers the whole activity, the sheet included.
- The choice survives wrong PINs, lockouts and dismissing the sheet.
- The request still expires 5 minutes after it was made; if it expires while the sheet is open, the sheet closes and step 1 shows the expired error.
