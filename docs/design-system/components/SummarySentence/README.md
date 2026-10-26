The Guardian's choice read back as one serif sentence: live at the end of step 1, then fixed at the top of the PIN sheet, so they know exactly what they are signing.

**Shows** in the `headline` style: "Instagram, *30 minutes*, once.", "Instagram, *1 hour a day* for 7 days." or "Instagram, *unlocked for good*." The duration (or "unlocked for good") is set in italic. When the app isn't installed on the Guardian's phone, the package name stands in, set in mono.

**The consumer provides** the current `AccessForm` and the app label if Krypt can find it on this phone (by package name), else the package.

**Rules**
- It updates live as the choice changes (`liveRegion = Polite`), and hides while a custom value is invalid.
- The same words go into the message the Guardian sends back.
