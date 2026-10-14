The PIN entry: a row of dots above Krypt's own numeric keypad. It is used on Set Guardian PIN, the Krypt Admin gate and the Guardian's approval screen.

**Shows:** one 12dp dot per possible digit, filled with `primary` as digits are typed. Required positions have an `outline` ring; optional ones (5-6, or 5-8 on the Guardian screen) have a fainter `outline-variant` ring. Below the dots is one message line. Under that is a 3×4 keypad of borderless keys (`key-height`, `radius-sm`, digits in the `keypad` style): 1-9, 0 and a delete key (`backspace`).

**The consumer provides** the digit count, the maximum (6, or 8 on the Guardian screen), enabled, busy, and the error text.

**States**
- Empty, typing, full.
- **Checking:** the filled dots breathe. The PIN check takes about 0.3s on purpose.
- **Wrong:** the dots turn `error`, shake once, then clear. The message reads "That PIN isn't right. 2 tries left." (plurals).
- **Locked out:** the keypad dims to `disabled-content` and LockoutCountdown takes the message line.
- **Not set up:** a message only, with no keypad.

**Rules**
- Digits are never drawn, not even for a moment. There is no show-PIN option.
- The keypad sits over a hidden field with `KeyboardType.NumberPassword` tagged `PIN_FIELD_TAG`, so hardware keyboards and TalkBack still work. TalkBack reads "Guardian PIN, 4 digits entered", never the digits.
- Digits live only in memory: never in saved state, wiped after every submit and whenever the screen closes or rotates.
- Keys give a light haptic tick, and no sound.
