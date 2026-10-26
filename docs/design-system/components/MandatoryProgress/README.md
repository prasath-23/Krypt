Two thin ticks at the top of the onboarding checklist, one per required permission. The optional ones are never counted.

**Shows:** two 3dp ticks that fill with `primary` as each required permission is allowed, and the line "1 of 2 required" in `body-small`. At 2 of 2 the line reads "2 of 2 required · ready" in `granted`.

**At the bottom of the checklist:** one primary button, **Start protecting**. It is disabled until 2 of 2, with "Allow the 2 required items to start." under it.

**The consumer provides** `mandatoryGranted`, `mandatoryTotal` and `canFinish`. "Start protecting" calls `tryFinish()`.

**Rules**
- Only the 2 required items move the ticks.
- The ticks are one `progressbar` for TalkBack: "1 of 2 required".
