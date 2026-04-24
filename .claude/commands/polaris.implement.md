---
description: Create an isolated workspace (worktree) for implementing a specific work package.
---

## Domain Expert Check (read before starting)

Read the WP frontmatter. If a `domain` field is present:

1. Look for the domain prompt file at `.polaris/skills/superpowers/<domain>.md` in the project root
2. If not found locally, look for `src/specify_cli/superpowers/prompts/<domain>.md` (the built-in package prompt)
3. If a prompt file is found, read its content. This is your **domain expertise** for this WP:
   - Follow the quality checklist for every subtask you complete
   - Actively avoid the listed common pitfalls
   - Meet the output expectations described in the prompt
4. If no prompt file is found for the domain value, proceed normally (no error)

This domain expertise applies to ALL your work on this WP, whether you use subagents or not. If you also spawn subagents (see Subagent Check below), each subagent gets its own group-level superpower on top of this WP-level domain expertise.

If no `domain` field is present in frontmatter, skip this section entirely.

---

## Subagent Check (read before starting)

Read the WP frontmatter before doing any work.

**If `subagents: true` is present in frontmatter:**

1. Read `subagent_groups` - each entry is either a list of subtask numbers (old format: `[1,2]`) or a dict with `tasks` (subtask numbers) and optional `superpower` (domain name, e.g. `"database-expert"`). Handle both formats.
2. For each group, spawn one subagent using the Agent tool with this prompt structure:
   - **Superpower context** (if group has a `superpower` field): Read the file `.polaris/skills/superpowers/<superpower-name>.md` if it exists. If not found there, check `src/specify_cli/superpowers/prompts/<superpower-name>.md`. If a prompt file is found, prepend its content as the FIRST section of the subagent prompt under a "## Domain Expert Role" heading. If no prompt file is found, skip this step (no error, proceed without superpower).
   - Feature context: read the first 30 lines of `polaris-specs/<feature>/spec.md` (problem statement + actors only)
   - Plan context: only the plan sections relevant to this group's subtasks
   - Assigned subtasks: only the subtasks in this group, copied in full detail from the WP file
   - Permitted files: explicit list of files this group may read and modify (derive from subtask descriptions)
   - Hard constraint: "Do not read or modify any file not in your permitted files list."
   - Output format: "When done, report: (1) list of files you modified, (2) one-sentence summary per subtask completed."
   - Depth constraint: "You are a subagent. Do NOT use the Agent tool. Do NOT spawn further subagents. Use only Read, Write, Edit, Bash, Glob, Grep tools."
3. Launch all subagents simultaneously - do not wait for one before starting the next
4. Wait for ALL subagents to complete before proceeding
5. Check for file conflicts - if any file was modified by more than one subagent:
   - Read all versions of the conflicting file
   - Produce a single merged version that satisfies all subtask requirements
   - Log a warning: "Conflict resolved in <filename> between subagent groups <X> and <Y>"
6. Commit all changes as a single consolidated commit covering all subagent work

**If `subagents: true` is NOT present:** proceed with normal sequential implementation below. No subagents. No change from standard behavior.

---

## Working Directory

**Telemetry**: Run: `polaris telemetry record implement --feature <slug> --phase start --agent {{AGENT_NAME}} --wp <WP_ID>`


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

**Telemetry**: Run: `polaris telemetry record implement --feature <slug> --phase complete --agent {{AGENT_NAME}} --wp <WP_ID>`
