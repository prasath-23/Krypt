The way in when a messenger won't make a Krypt link tappable: a tertiary button that reads the clipboard and opens the link it finds.

- **On Krypt's own screens** (onboarding and the PIN gate): **Paste a link** with `link`. When nothing is found, a snackbar says: "No Krypt link found. Copy the whole message first."
- **On the lock screen** (approvals only): **Paste reply** with `content_paste`. When no reply is found, an inline `error` line says: "No reply on the clipboard. Copy your Guardian's whole message, then try again." There is no snackbar here.

**The consumer provides** `pasteKryptLinkAction(approvalsOnly, onNoLink)`.

**Rules**
- The snackbar is used for this case only.
- On the PIN gate, one line explains it: "Got a Krypt link that won't open? Copy it and tap Paste a link."
