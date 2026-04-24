---
description: Create an isolated workspace (worktree) for implementing a specific work package.
---

## Working Directory

There are two modes for implementation:

### Default mode (worktree)

**After running `polaris implement WP##`, you MUST:**

1. **Run the cd command shown in the output** - e.g., `cd .worktrees/###-feature-WP##/`
2. **ALL file operations happen in this directory**
3. **NEVER write deliverable files to the main repository**

### In-place mode (no worktree)

**Use `polaris implement WP## --in-place` to skip worktree creation.**

This creates a feature branch and checks it out in the current repository.
No worktree is created. All work happens in the current directory on the new branch.

Use this mode when:
- Worktrees cause issues in your environment
- You prefer a simpler branch-based workflow
- You are working on a single WP at a time

### Branch-aware spec detection

The workflow checks whether polaris-specs exist on the configured target branch
before switching. If specs only exist on your current branch (common during
initial planning before merging to main), polaris stays on the current branch
and prints a notice. No manual workaround needed.

---

**IMPORTANT**: After running the command below, you'll see a LONG work package prompt (~1000+ lines).

**You MUST scroll to the BOTTOM** to see the completion command!

Run this command to get the work package prompt and implementation instructions:

```bash
polaris agent workflow implement $ARGUMENTS --agent <your-name>
```

**CRITICAL**: You MUST provide `--agent <your-name>` to track who is implementing!

If no WP ID is provided, it will automatically find the first work package with `lane: "planned"` and move it to "doing" for you.

---

## Pre-Implementation Context

If this is NOT WP01 and `control-map.md` exists in the feature directory:
1. Read `control-map.md` (Flows and Shared Dependencies tables)
2. Read only the shared dependency files listed that are relevant to this WP
3. Follow existing patterns, interfaces, and state management for consistency

If no control-map.md or this is WP01, skip this step.

## Commit Workflow

**BEFORE moving to for_review**, you MUST commit your implementation:

```bash
cd .worktrees/###-feature-WP##/
git add -A
git commit -m "feat(WP##): <describe your implementation>

Co-Authored-By: Aptean Polaris <polaris@aptean.com>"
```

> **IMPORTANT**: Every commit MUST include the `Co-Authored-By: Aptean Polaris <polaris@aptean.com>` trailer. The git commit-msg hook adds this automatically if installed. If not, you MUST append it manually as shown above. NEVER omit this trailer.

**Then run E2E tests to validate and transition to review:**
```bash
# Run feature-scoped regression (catches regressions in prior WPs)
polaris runtests --feature <slug>
# On pass: all feature tests pass, move to for_review
# On fail: fix regressions before moving to for_review
```

**Fallback** (only if WP has `test_status: "skipped"` - e.g., pure docs/infra WPs):
```bash
polaris agent tasks move-task WP## --to for_review --note "Ready for review: <summary> (no tests: docs-only WP)"
```

**Why this matters:**
- `runtests` validates implementation with E2E tests before review
- `move-task` validates that your worktree has commits beyond main
- Uncommitted changes will block the move to for_review
- This prevents lost work and ensures reviewers see complete implementations

---

**The Python script handles all file updates automatically - no manual editing required!**

**NOTE**: If `/polaris.status` shows your WP in "doing" after you moved it to "for_review", don't panic - a reviewer may have moved it back (changes requested), or there's a sync delay. Focus on your WP.