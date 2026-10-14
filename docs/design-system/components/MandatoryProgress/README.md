Three thin ticks at the top of the onboarding checklist, one per required permission. The optional ones are never counted.

**Shows:** three 3dp ticks that fill with `primary` as each required permission is allowed, and the line "2 of 3 required" in `body-small`. At 3 of 3 the line reads "3 of 3 required · ready" in `granted`.

**At the bottom of the checklist:** one primary button, **Start protecting**. It is disabled until 3 of 3, with "Allow the 3 required items to start." under it.

**The consumer provides** `mandatoryGranted`, `mandatoryTotal` and `canFinish`. "Start protecting" calls `tryFinish()`.

**Rules**
- Only the 3 required items move the ticks.
- The ticks are one `progressbar` for TalkBack: "2 of 3 required".
