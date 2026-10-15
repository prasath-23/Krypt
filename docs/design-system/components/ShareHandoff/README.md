The request hand-off on the lock screen: Request access opens the share sheet, then a three-step timeline says what is happening, and the action bar changes to the next useful action.

**Idle / expired / failed:** primary **Request access** (with `share`), tertiary row **Paste reply** · **Back to home**. Under the button: "Pick any chat to send the request to your Guardian."
**Preparing:** the primary shows a spinner and reads "Preparing request".
**Sent (waiting):** a vertical timeline with a 1dp connecting line:
1. Request sent ✓
2. Waiting for your Guardian · Expires in 4:59, ticking in `mono-time`.
3. Tap the link in their reply, or copy it and tap Paste reply. Instagram opens.

and the action bar becomes primary **Paste reply** (with `content_paste`), tertiary row **Send again** · **Back to home**. There is no second Paste reply inside the timeline. Send again issues a fresh request exactly like Request access, so the primary never looks like the first send failed.
**Not set up / failed:** one `error` line in place of the timeline, and the bar returns to the idle state.

**The consumer provides** `RequestStatus`, the app's name, and the time the request was sent (the 5-minute expiry is counted in the UI). At 0:00 the timeline gives way to "This request expired. Ask again." and the bar returns to the idle state.

**Rules**
- The share sheet is titled "Send your request". It excludes Krypt's own "Open in Krypt" target and carries the whole `krypt://request` link.
- Tapping the link in the messenger stays the main path; Paste reply is the in-app action while waiting.
- The Guardian side mirrors it: Approve and send → share sheet → "Sent. You can close this screen."
