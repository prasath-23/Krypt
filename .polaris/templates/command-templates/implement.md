---
description: Create an isolated workspace (worktree) for implementing a specific work package.
---

# /polaris.implement - Create Workspace for Work Package

**Version**: 0.11.0+
**Purpose**: Create an isolated workspace (git worktree) for implementing a specific work package.

## ⚠️ CRITICAL: Working Directory Requirement

**After running `polaris implement WP##`, you MUST:**

1. **Run the cd command shown in the output** - e.g., `cd .worktrees/###-feature-WP##/`
2. **ALL file operations happen in this directory** - Read, Write, Edit tools must target files in the workspace
3. **NEVER write deliverable files to the main repository** - This is a critical workflow error

**Why this matters:**
- Each WP has an isolated worktree with its own branch
- Changes in main repository will NOT be seen by reviewers looking at the WP worktree
- Writing to main instead of the workspace causes review failures and merge conflicts

**Verify you're in the right directory:**
```bash
pwd
# Should show: /path/to/repo/.worktrees/###-feature-WP##/
```

---

## Pre-Implementation Context

If this is NOT WP01 and `control-map.md` exists in the feature directory:
1. Read `control-map.md` (Flows and Shared Dependencies tables)
2. Read only the shared dependency files listed that are relevant to this WP
3. Follow existing patterns, interfaces, and state management for consistency

If no control-map.md or this is WP01, skip this step.

---

### Branch-aware spec detection

The workflow checks whether polaris-specs exist on the configured target branch
before switching. If specs only exist on your current branch (common during
initial planning before merging to main), polaris stays on the current branch
and prints a notice. No manual workaround needed.

---

## CRITICAL: This is a TWO-STEP Command

**Step 1**: Get the WP prompt and implementation instructions
```bash
polaris agent workflow implement WP## --agent __AGENT__
```
This displays the full WP prompt with detailed requirements and shows:
```
WHEN YOU'RE DONE:
================================================================================
✓ Implementation complete and tested:
  polaris agent tasks move-task WP## --to for_review --note "Ready for review"
```

**Step 2**: Create the workspace (if needed) and implement according to the prompt
```bash
polaris implement WP##              # No dependencies (branches from main)
polaris implement WP## --base WPXX  # With dependencies (branches from base WP)
```

## Completion Requirements

**Your work is NOT complete until**:
1. ✅ All subtasks in WP prompt are finished
2. ✅ Changes committed to the WP workspace
3. ✅ **Run feature-scoped regression**: `polaris runtests --feature <slug>` (catches regressions in prior WPs)

**Default flow**: implement → `polaris runtests --feature <slug>` → auto-moves to `for_review` on pass
**Fallback** (only for WPs with `test_status: "skipped"`): `polaris agent tasks move-task WP## --to for_review --note "No tests: docs/infra WP"`

**The WP file location determines status**:
- In `tasks/WP##-*.md` with `lane: "doing"` = IN PROGRESS (not done)
- Need to move to `for_review` lane when complete

## When to Use

After `/polaris.tasks` generates work packages in the main repository:
- Planning artifacts (spec, plan, tasks) are already in main
- Run `polaris agent workflow implement WP01 --agent __AGENT__` to get the full prompt
- Run `polaris implement WP01` to create a workspace for the first WP
- Run `polaris implement WP02 --base WP01` if WP02 depends on WP01
- Each WP gets its own isolated worktree in `.worktrees/###-feature-WP##/`

## Workflow

**Planning Phase** (main repo, no worktrees):
```
/polaris.specify → Creates spec.md in main
/polaris.plan → Creates plan.md in main
/polaris.tasks → Creates tasks/*.md in main
```

**Implementation Phase** (creates worktrees on-demand):
```
polaris implement WP01 → Creates .worktrees/###-feature-WP01/
polaris implement WP02 --base WP01 → Creates .worktrees/###-feature-WP02/
```

## Examples

**Independent WP** (no dependencies):
```bash
polaris implement WP01
# Creates: .worktrees/010-workspace-per-wp-WP01/
# Branches from: main
# Contains: Planning artifacts (spec, plan, tasks)
```

**Dependent WP**:
```bash
polaris implement WP02 --base WP01
# Creates: .worktrees/010-workspace-per-wp-WP02/
# Branches from: 010-workspace-per-wp-WP01 branch
# Contains: Planning artifacts + WP01's code changes
```

## Validation

The command validates:
- Base workspace exists (if --base specified)
- Suggests --base if WP has dependencies in frontmatter
- Errors if trying to branch from a non-existent base

## Parallel Development

Multiple agents can implement different WPs simultaneously:
```bash
# Agent A
polaris implement WP01

# Agent B (in parallel)
polaris implement WP03

# Both work in isolated worktrees without conflicts
```

## Dependencies

Work package dependencies are declared in frontmatter:
```yaml
dependencies: ["WP01"]  # This WP depends on WP01
```

The implement command reads this field and validates the --base flag matches.

## Complete Implementation Workflow

**ALWAYS follow this sequence**:

```bash
# 1. Get the full WP prompt and instructions
polaris agent workflow implement WP## --agent __AGENT__

# 2. Read the "WHEN YOU'RE DONE" section at the top of the prompt
# It will show exactly what command to run when complete:
#   polaris agent tasks move-task WP## --to for_review --note "..."

# 3. Create workspace (if not exists)
polaris implement WP##              # Or with --base if dependencies

# 4. Navigate to workspace
cd .worktrees/###-feature-WP##/

# 5. Implement according to WP prompt
# ... write code, commit changes ...

# 6. Run feature-scoped regression (REQUIRED - catches regressions in prior WPs)
polaris runtests --feature <slug>
# On pass: all feature tests pass, move to for_review
# On fail: fix regressions before moving to for_review

# 6b. Fallback (ONLY if WP has test_status: "skipped" - e.g., docs/infra WPs)
# polaris agent tasks move-task WP## --to for_review --note "No tests: docs/infra WP"
```

**IMPORTANT**: Step 6 is MANDATORY. The WP is NOT complete until moved to `for_review` lane (either via tests or manually).

## Lane Status

Work packages move through lanes:
- `planned` → Initial state after `/polaris.tasks`
- `doing` → Agent is implementing (automatically set by workflow command)
- `testing` → E2E tests running (set by `polaris runtests`)
- `for_review` → Tests passed or manually moved, waiting for review
- `done` → Review passed, WP complete

**Testing lane transitions** (automatic via `polaris runtests`):
- `doing` → `testing` → `for_review` (tests pass)
- `doing` → `testing` → `doing` (tests fail, with failure details)

**Check current lane**:
```bash
polaris agent tasks list-tasks --lane doing
```

## Troubleshooting

**Error: "Base workspace WP01 does not exist"**
- Solution: Implement WP01 first: `polaris implement WP01`

**Error: "WP02 has dependencies. Use: polaris implement WP02 --base WP01"**
- Solution: Add --base flag as suggested

**Warning: "Base branch has changed. Consider rebasing..."**
- Solution: Run suggested rebase command

**"I finished implementing but nothing happened"**
- Check: Did you move to for_review? `polaris agent tasks move-task WP## --to for_review`
- The WP file must be moved to for_review lane for the workflow to continue

**"Status board shows 'doing' but I just moved to 'for_review'"**
- This is normal! Status is tracked in the target branch. A reviewer may have already moved it back to "doing" (changes requested), or there's a sync delay. Don't panic - focus on your WP.
