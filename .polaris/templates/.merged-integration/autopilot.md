---
description: Run the full Aptean application pipeline autonomously with retry logic.
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Quick Mode

If `--quick` is passed: use quick mode for the specify stage (2 questions max, combined spec+plan generation). All downstream stages (tasks, implement, test, review, accept, merge) run normally with full quality gates.

## Discovery

1. **Context**: New application (full pipeline), existing feature (resume from current phase), or `--resume` (from saved state).
2. **Feature slug**: Detect from directory, arguments, or ask.
3. **Retry limit**: Default 3 attempts per stage.
4. **On failure**: Default skip-and-continue (skip failed WP, continue with independents).
5. **Mode**: `--quick` activates quick mode for specify phase (record in state).

End discovery with `WAITING_FOR_AUTOPILOT_INPUT`.

## Pipeline Stages

### Stage 1: Setup (new apps only)

Run `/polaris.setup` with Aptean defaults (Suisse Intl, --aptean-* CSS, teal #1A7B7E, Django 6+/Vite+React 19+/PostgreSQL 17+, AKS+Helm, health probes, CI/CD). Skip if `.polaris/` exists.

### Stage 2: Build

**2a. Specify + Plan** (`/polaris.specify`): Create spec with auto-review, generate plan. Skip if both `spec.md` and `plan.md` exist.

**2b. Tasks** (`/polaris.tasks`): Generate WPs and task breakdowns. Run `polaris agent feature finalize-tasks --json` to parse deps, update frontmatter, generate E2E test skeletons. Skip if `tasks.md` exists.

**2c. Test Plan**: Create `polaris-specs/<slug>/test-plan.md` with sections: Unit Tests (per WP, min 2 each), Integration Tests (cross-WP), Acceptance Tests (from spec), Edge Cases. Rules: all names start with `test_`, all unique, map to spec criteria. Skip if exists.

**2d. Implement**: For each WP in dependency order:
1. `polaris implement <WP_ID> --feature <slug>` (add `--base <dep>` if deps exist). Verify WP moved to `doing`.
2. Follow implementation prompt from `polaris-specs/<slug>/tasks/<WP_ID>.md`
3. Implement fully, run tests, commit
4. On failure: retry up to limit, then mark failed and skip dependents

### Stage 3: Ship

**3a. Test Execution** (per WP after implementation):

1. Move to testing: `polaris agent tasks move-task <WP_ID> --to testing --feature <slug>`
2. Run project tests: `python .polaris/scripts/tasks/run_tests.py --project-root . --wp <WP_ID> --json`. Check `success: true`, `failed: 0`. If `no_tests: true`, log warning and continue (not a failure).
3. Run E2E tests if `.spec.js` files exist: `polaris runtests --wp <WP_ID> --feature <slug>`
4. On failure: move back to doing, fix, retry (max 3). After exhausting retries, mark failed.
5. Validate test plan coverage: `python .polaris/scripts/tasks/run_tests.py --project-root . --validate-plan polaris-specs/<slug>/test-plan.md --no-run --json`. Gate: `coverage_percent >= 80`.
6. On success: move to for_review.

**Kanban flow**: planned -> doing -> testing -> (doing if fail) -> testing -> for_review -> done

**3b. Review** (`/polaris.review`): For each WP in for_review. On approval, move to done. On rejection, move to doing, fix, restart from 3a.

**3c. Accept** (`/polaris.accept`): Once all WPs pass review.

**3d. Merge** (`/polaris.merge`): Preflight, merge all WPs, clean up worktrees.

## State Persistence

Save to `.polaris/autopilot-state.json`:

```json
{
  "feature_slug": "<slug>",
  "current_stage": "build.implement",
  "wp_order": ["WP01", "WP02"],
  "completed_wps": ["WP01"],
  "failed_wps": {},
  "current_wp": "WP02",
  "current_attempt": 1,
  "max_attempts": 3,
  "mode": "standard|quick",
  "on_failure": "skip-and-continue",
  "started_at": "<ISO>",
  "updated_at": "<ISO>",
  "build": { "test_plan": { "status": "complete|pending|skipped" } },
  "ship": { "test_execution": { "status": "passed|failed|skipped", "passed": 0, "failed": 0, "total": 0, "coverage_percent": 0 } }
}
```

Resume: `/polaris.autopilot --resume`. Abort: `/polaris.autopilot --abort`.

## Final Summary

Display: pipeline stages, succeeded/failed/skipped WP counts, per-WP test results table (tests run/passed/failed/coverage), failed WP details with error and impact, next steps.

## Error Handling

Never halt silently. Never lose work (committed code preserved). State updated atomically. All commands are cross-platform (Polaris CLI + git).
