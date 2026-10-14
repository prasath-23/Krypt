The request hand-off: one primary button opens the share sheet, then a three-step timeline says what is happening and what comes next.

**Before:** **Request access** (with `share`), and under it: "Pick any chat to send the request to your Guardian."
**Preparing:** the button shows a spinner and reads "Preparing request".
**Sent:** a vertical timeline with a 1dp connecting line:
1. Request sent ✓
2. Waiting for your Guardian · Expires in 4:59, ticking in `mono-time`.
3. Tap the link in their reply, or copy it and tap Paste reply. Instagram opens.

A tertiary **Paste reply** sits under the timeline.
**Not set up / failed:** one `error` line in place of the timeline, and the button works again.

**The consumer provides** `RequestStatus`, the app's name, and the time the request was sent (the 5-minute expiry is counted in the UI).

**Rules**
- The share sheet is titled "Send your request". It excludes Krypt's own "Open in Krypt" target and carries the whole `krypt://request` link.
- At 0:00 the timeline gives way to "This request expired. Ask again." and the button comes back.
- The Guardian side mirrors it: Approve and send → share sheet → "Sent. You can close this screen."
