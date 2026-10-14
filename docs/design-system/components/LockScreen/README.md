What appears instead of a locked app: a calm, left-aligned page that follows the phone's light or dark theme, with one way in.

**Shows**, in a column at most `column-max` wide with a `space-5` gutter:
- **Top:** the eyebrow "PROTECTED BY KRYPT" with the mark.
- **The app:** its 56dp icon tile (`radius-lg`), its name and its package in mono.
- **The reason:** an `icon-lg` icon, a one-word serif statement in `display-large`, and one line of `body-large`.
- **Pinned to the bottom:** the primary **Request access** button, then a row with **Paste reply** on the left and **Back to home** on the right.

| Reason | Icon | Statement | Line |
|---|---|---|---|
| Locked | `lock` (`on-surface`) | Locked. | Your Guardian can open Instagram for you. Ask, and it opens the moment they approve. |
| Daily used up | `hourglass_bottom` (`caution`) | Done for today. | Today's 60 minutes are used. If you need more, ask your Guardian for extra time. |
| Daily paused | `schedule` (`caution`) | Paused. | Daily time needs automatic date and time. Turn it on in Settings, or ask your Guardian. |

The ShareHandoff timeline appears under the reason once a request is sent.

**The consumer provides** `LockScreenState` (`lockedPackage`, `reason`, `request`), the app's label and icon, and `askGuardian()`.

**Rules**
- There is no PIN entry here, ever. Back and Back to home both go to the launcher, never to the app.
- **First frame:** the window background is plain `background` (a `values` and a `values-night` colour). The very first frame is therefore the finished page's background, and nothing flashes.
- Update the reason in place, and close by itself when an approval or daily time arrives.
- At 200% font scale the column scrolls, and the action area stays pinned to the bottom.
