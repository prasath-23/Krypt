---
description: Generate grouped work packages with actionable subtasks and matching prompt files for the feature in one pass.
---

## User Input

**Telemetry**: Run: `polaris telemetry record tasks --feature <slug> --phase start --agent {{AGENT_NAME}}`


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

## Domain Assignment

For every WP, assign a primary domain based on the majority of its subtasks. Add a `domain` field to the WP frontmatter YAML.

Available domains (must match exactly):
- `database` - Database migrations, schema design, queries, indexes
- `api-design` - REST/GraphQL endpoints, validation, error handling, auth
- `frontend-craft` - UI components, CSS, accessibility, responsive design
- `backend-logic` - Business logic, services, data processing, error handling
- `testing-specialist` - Test strategy, fixtures, mocking, coverage
- `devops-infra` - CI/CD, Docker, Helm, Kubernetes, infrastructure
- `documentation` - API docs, user guides, architecture docs, changelogs

Rules:
1. Every WP MUST have exactly one domain (this is required, not optional)
2. Pick the domain matching the majority of the WP's subtasks
3. If truly mixed with no clear majority, use `backend-logic` as default

Example frontmatter:
```yaml
domain: "database"
```

## Subagent Eligibility Evaluation

After grouping subtasks into WPs, evaluate each WP for subagent eligibility. A WP is eligible ONLY if ALL of the following are true:

1. The WP has 5 or more subtasks total
2. At least 3 subtasks are verifiably independent - they touch different files, have no sequential dependency between them, and could be done by different developers without coordination
3. Each candidate independent subtask is substantial enough to justify a separate agent session (not a trivial 1-2 line change)
4. You can confidently partition the independent subtasks into groups with ZERO file overlap between groups - no two groups touch the same file

If ALL four criteria are met, add these fields to the WP frontmatter:

```yaml
subagents: true
subagent_groups:
  - tasks: [1, 2]
    superpower: "database-expert"
  - tasks: [3, 4]
    superpower: "api-design"
  - tasks: [5, 6]
```

Where each entry contains `tasks` (the subtask T-numbers for one parallel group) and an optional `superpower` (domain expert to inject). Sequential subtasks (where subtask N depends on the output of subtask N-1) must be placed in the same group or kept for the main agent after subagents complete.

### Superpower Assignment

For each subagent group in an eligible WP, evaluate the domain of its subtasks and assign the most appropriate superpower. Available built-in superpowers:

- `database-expert` - Database migrations, schema design, query optimization, index strategy
- `api-design` - REST/GraphQL API design, OpenAPI specs, endpoint validation, auth patterns
- `frontend-craft` - React/Vue components, CSS, accessibility, responsive design
- `backend-logic` - Business logic, service layer, data processing, error handling
- `testing-specialist` - Test strategy, fixtures, mocking, coverage, edge cases
- `devops-infra` - CI/CD pipelines, Docker, Helm, Kubernetes, infrastructure as code
- `documentation` - API docs, user guides, architecture docs, changelogs

Assignment rules:
1. If the majority of a group's subtasks clearly belong to one domain, assign that superpower
2. If a group spans multiple domains with no clear majority, omit the superpower field (the group runs as a generic subagent)
3. Never force-assign a superpower - when in doubt, leave it out

**Safety rules (non-negotiable)**:
- When in doubt, do NOT set `subagents: true`. Safety over speed.
- If you cannot guarantee zero file overlap between groups, do NOT flag the WP.
- Unflagged WPs execute sequentially as normal - there is no penalty for not flagging.

Context: {ARGS}

The combination of tasks.md and prompt files must enable any engineer to pick up a WP and deliver it end-to-end.


**Telemetry**: Run: `polaris telemetry record tasks --feature <slug> --phase complete --agent {{AGENT_NAME}}`
