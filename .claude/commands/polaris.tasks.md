---
description: Generate grouped work packages with actionable subtasks and matching prompt files for the feature in one pass.
---


## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Working Directory

Run from planning repository root. NO worktrees created. All output goes to `polaris-specs/###-feature/tasks/` and is committed to the target branch. Worktrees are created later via `polaris implement WP##`.

Verify you're on the target branch:
```bash
git branch --show-current
```

## WP Sizing Rules (CRITICAL)

| Metric | Target | Max | Action if exceeded |
|--------|--------|-----|--------------------|
| Subtasks/WP | 3-7 | 10 | SPLIT the WP |
| Prompt lines/WP | 200-500 | 700 | SPLIT the WP |
| Subtask detail | 30-70 lines | - | Include purpose, steps, files, validation |

- Let complexity dictate WP count (20+ WPs is fine for complex features)
- Each WP must be independently implementable with a single focused goal
- Better to have 20 focused WPs than 5 overwhelming ones

## Steps

### 1. Setup

```bash
polaris agent feature check-prerequisites --json --paths-only --include-tasks
```

Capture `FEATURE_DIR` (ABSOLUTE path). Use it for ALL file operations.
- Correct: `FEATURE_DIR/tasks/WP01-slug.md`
- Wrong: `tasks/WP01-slug.md`, `/tasks/WP01-slug.md`, `FEATURE_DIR/tasks/planned/WP01.md`

### 2. Load Design Documents

From `FEATURE_DIR`: spec.md and plan.md (required), data-model.md, contracts/, research.md (optional). Scale effort to feature complexity.

### 3. Derive Subtasks

Create complete list with IDs T001, T002, etc. Include implementation steps, tests, migrations, operational work. Mark parallel-safe items with `[P]`.

### 4. Group into Work Packages

Group related subtasks into WPs (WP01, WP02, ...) following sizing rules above.

Grouping principles:
- Root each WP in a single user story or cohesive subsystem
- Sequence: setup -> foundational -> story phases -> polish
- Record: priority, success criteria, risks, dependencies, included subtasks
- Every subtask in exactly one WP

### 5. Write tasks.md

Write to `FEATURE_DIR/tasks.md` with WP sections containing: summary, subtask checklist, implementation sketch, parallel opportunities, dependencies.

### 6. Generate WP Prompt Files

Create `FEATURE_DIR/tasks/WPxx-slug.md` for each WP (FLAT directory, no subdirectories).

Each prompt uses the task prompt template with:
- Frontmatter: `work_package_id`, `subtasks` array, `lane: "planned"`, `dependencies`
- Objective, context, detailed per-subtask guidance
- Test strategy, Definition of Done, risks, reviewer guidance

Lane status is in the `lane:` frontmatter field only - never in directory structure.

**Validate each prompt**: if >700 lines, go back and split.

### 7. Finalize

```bash
polaris agent feature finalize-tasks --json
```

This parses dependencies, updates frontmatter, validates (no cycles), and COMMITS automatically. **DO NOT run git commit after this** - check JSON output for `commit_created` and `commit_hash`.

### 8. Report

Provide: WP count, subtask tallies, size distribution, size validation (flag any >700 lines), parallelization opportunities, MVP scope, next command.

## Dependencies (0.11.0+)

Parse from tasks.md (explicit phrases, phase grouping). Write to WP frontmatter:

```yaml
dependencies: ["WP01"]
```

Include correct implementation command in each WP prompt:
- No deps: `polaris implement WP01`
- With deps: `polaris implement WP02 --base WP01`

## Task Rules

- E2E tests required for every feature. Each WP must include test scenarios. Only pure docs/infra WPs may skip (mark `test_status: "skipped"` with justification).
- Place test files in `tests/{feature-slug}/` directory for feature-scoped regression testing.
- Subtask granularity: one clear action (not "add import" nor "build entire API")
- Prompt detail: include purpose, steps, files to create/modify, validation checklist, edge cases
- Think like a reviewer: every requirement must be objectively verifiable

Context: $ARGUMENTS

The combination of tasks.md and prompt files must enable any engineer to pick up a WP and deliver it end-to-end.
