After too many wrong PINs, a thin ring counts down until the next try. It replaces the keypad's message line.

**Shows:** a 120dp ring (2dp `outline-variant` track, `primary` arc) emptying as time passes, with the remaining time inside in `mono-countdown` ("0:28", or "7 min" past 60s). One line under it: "Too many wrong PINs. Try again when the timer ends."

**The consumer provides** the remaining milliseconds (`LockedOut(ms)` or `lockoutRetryInMs`). The ring ticks every second and calls `lockoutElapsed()` when it reaches zero.

**Rules**
- The keypad and the primary button are disabled until the ring is empty.
- The schedule is fixed and shared by the gate and the Guardian screen, and it survives restarts. 1st-2nd wrong PIN: tries left. 3rd: 30s. 4th-5th: 2 min. 6th-7th: 10 min. 8th and later: 1 hour.
- Announce the lockout once; don't announce every tick.
