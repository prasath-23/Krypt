---
description: Perform structured code review and kanban transitions for completed task prompt files
---


**IMPORTANT**: After running the command below, you'll see a LONG work package prompt (~1000+ lines).

**You MUST scroll to the BOTTOM** to see the completion commands!

Run this command to get the work package prompt and review instructions:

```bash
polaris agent workflow review $ARGUMENTS --agent <your-name>
```

**CRITICAL**: You MUST provide `--agent <your-name>` to track who is reviewing!

If no WP ID is provided, it will automatically find the first work package with `lane: "for_review"` and move it to "doing" for you.

## Separation of Duties Check

Before proceeding with review, verify SoD compliance:
1. Resolve your identity: run `git config user.email` to determine who you are.
2. Check the audit trail at `.polaris/audit-trail/<feature-slug>.jsonl` for this WP's implementer (the actor who moved the WP to "doing").
3. If your email matches the implementer:
   - If `quality.sod_enforcement` is enabled in `.polaris/config.yaml`: STOP and reject with "Reviewer must be different from implementer".
   - If disabled (default): warn "SoD: reviewer is same as implementer" but allow the review to proceed.
   - Override: use `--self-review` flag with a justification string. The override and justification will be recorded in the audit trail with `override: true`.
4. Record your reviewer identity in the audit trail by running:
   `polaris agent tasks move-task WP## --to done` (the move-task command automatically appends an audit entry).

## Dependency checks (required)

- dependency_check: If the WP frontmatter lists `dependencies`, confirm each dependency WP is merged to main before you review this WP.
- dependent_check: Identify any WPs that list this WP as a dependency and note their current lanes.
- rebase_warning: If you request changes AND any dependents exist, warn those agents to rebase and provide a concrete command (example: `cd .worktrees/FEATURE-WP02 && git rebase FEATURE-WP01`).
- verify_instruction: Confirm dependency declarations match actual code coupling (imports, shared modules, API contracts).

**After reviewing, scroll to the bottom and run ONE of these commands**:
- ✅ Approve: `polaris agent tasks move-task WP## --to done --note "Review passed: <summary>"`
- ❌ Reject: Write feedback to the temp file path shown in the prompt, then run `polaris agent tasks move-task WP## --to planned --review-feedback-file <temp-file-path>`

**The prompt will provide a unique temp file path for feedback - use that exact path to avoid conflicts with other agents!**

**The Python script handles all file updates automatically - no manual editing required!**
