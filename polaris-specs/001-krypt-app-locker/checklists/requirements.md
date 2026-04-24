# Requirements Quality Checklist --- Krypt

Validates that `spec.md` meets Polaris spec-quality gates before planning.

## Spec-content gates

- [x] Spec has no implementation details (no Kotlin class names, no Android SDK symbols appear in FR rows --- they're abstracted as "interception layer", "deep-link URI", etc.).
- [x] Every mandatory section (Summary, User Roles, User Scenarios, Functional Requirements, Success Criteria, Key Entities, Out of Scope, Assumptions) is present and non-empty.
- [x] Unused optional sections have been removed (none were pulled in empty).

## Functional-requirement gates

- [x] Each FR is testable --- either behaviour ("MUST intercept", "MUST post a notification") or property ("MUST NOT declare INTERNET").
- [x] FRs are numbered (FR-001 .. FR-016).
- [x] FRs do not prescribe implementation choice (e.g. FR-008 specifies a work-factor target, not the specific KDF library).

## Success-criteria gates

- [x] Each SC is measurable (latency in ms, packets observed, iterations/runtime, package count, wall-clock seconds).
- [x] Each SC is tech-agnostic --- phrased so a tester could verify it without reading the code.
- [x] SC-001, SC-003 name specific target hardware class (Pixel-7-class) to avoid "works on my machine".

## User-scenario gates

- [x] 7 scenarios (US-1 through US-7) cover setup, default-deny, interception, Guardian approval, Subject unlock consumption, uninstall, fully-offline.
- [x] Each scenario has numbered steps and a clear success outcome.

## Open-items gate (max 3 `[NEEDS CLARIFICATION]`)

- [x] 2 `[NEEDS CLARIFICATION]` markers (pairing UX, PIN-rotation semantics) --- under the cap.
- [x] Each marker states the default the planner will pick absent other input.

## Audit

Overall: spec passes quality gates. Proceed to `/polaris.plan`.
