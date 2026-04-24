---
description: Perform structured code review and kanban transitions for completed task prompt files
---

## Step 1: Claim the WP for review

Run this command to move the WP to "doing" and get the workspace:

```bash
polaris agent workflow review $ARGUMENTS --agent <your-name>
```

**CRITICAL**: You MUST provide `--agent <your-name>` to track who is reviewing!

If no WP ID is provided, it will automatically find the first work package with `lane: "for_review"` and move it to "doing" for you.

## Step 2: Separation of Duties Check

**Telemetry**: Run: `polaris telemetry record review --feature <slug> --phase start --agent {{AGENT_NAME}} --wp <WP_ID>`

Before proceeding with review, verify SoD compliance:
1. Resolve your identity: run `git config user.email` to determine who you are.
2. Check the audit trail at `.polaris/audit-trail/<feature-slug>.jsonl` for this WP's implementer (the actor who moved the WP to "doing").
3. If your email matches the implementer:
   - If `quality.sod_enforcement` is enabled in `.polaris/config.yaml`: STOP and reject with "Reviewer must be different from implementer".
   - If disabled (default): warn "SoD: reviewer is same as implementer" but allow the review to proceed.
   - Override: use `--self-review` flag with a justification string. The override and justification will be recorded in the audit trail with `override: true`.
4. Record your reviewer identity in the audit trail by running:
   `polaris agent tasks move-task WP## --to done` (the move-task command automatically appends an audit entry).

## Step 3: Dependency checks (required)

- dependency_check: If the WP frontmatter lists `dependencies`, confirm each dependency WP is merged to main before you review this WP.
- dependent_check: Identify any WPs that list this WP as a dependency and note their current lanes.
- rebase_warning: If you request changes AND any dependents exist, warn those agents to rebase and provide a concrete command (example: `cd .worktrees/FEATURE-WP02 && git rebase FEATURE-WP01`).
- verify_instruction: Confirm dependency declarations match actual code coupling (imports, shared modules, API contracts).

## Step 4: Read the implementation code

Switch to the WP branch and read ALL changed files. Use `git diff` to see what was changed.

## Step 5: Multi-Persona Review Passes (MANDATORY)

You MUST perform these 3 review passes sequentially. Each pass adopts a different specialist mindset. Do NOT skip any pass. Do NOT combine passes. Output each pass with its heading and verdict.

**Config check**: Read `.polaris/config.yaml` for a `review_passes` section. If present, use its `enabled` and `required` settings per pass. If not present, default: all 3 passes enabled, all required.

### Pass 1: Security Review

**Load expertise**: Read `.polaris/skills/superpowers/security-reviewer.md` if it exists in the project root. If not found, read the built-in prompt at `src/specify_cli/superpowers/prompts/security-reviewer.md`. Use its focus areas, quality checklist, and common pitfalls as your review criteria for this pass. If neither file is found, use the checklist below as fallback.

Adopt the mindset of an **Application Security Engineer**. Review ALL changed code for:

1. **Injection vulnerabilities** - SQL injection, XSS, command injection, path traversal
2. **Authentication & authorization** - Missing auth checks, privilege escalation, broken access control
3. **Secrets & credentials** - Hardcoded API keys, tokens, passwords, connection strings in code or config
4. **Input validation** - Missing or insufficient validation at system boundaries (API inputs, form data, URL params)
5. **Data exposure** - Sensitive data in logs, error messages, API responses, or code comments

For each finding, report: file, line number, vulnerability type, severity (Critical/High/Medium/Low), and suggested fix.

**Pass 1 Verdict: PASS or FAIL** (FAIL if any Critical or High severity finding)

---

### Pass 2: Performance Review

**Load expertise**: Read `.polaris/skills/superpowers/perf-engineer.md` if it exists in the project root. If not found, read the built-in prompt at `src/specify_cli/superpowers/prompts/perf-engineer.md`. Use its focus areas, quality checklist, and common pitfalls as your review criteria for this pass. If neither file is found, use the checklist below as fallback.

Adopt the mindset of a **Performance Engineer**. Review ALL changed code for:

1. **Database patterns** - N+1 queries, missing indexes on queried columns, unbounded SELECT without LIMIT
2. **Algorithm complexity** - O(n^2) or worse in loops, nested iterations over large collections
3. **Resource management** - Unclosed connections/streams/file handles, missing cleanup in error paths
4. **Caching** - Missing cache for repeated expensive operations, no cache invalidation strategy
5. **Memory** - Growing collections without bounds, event listeners not removed, large object retention

For each finding, report: file, line number, issue type, impact (High/Medium/Low), and suggested fix.

**Pass 2 Verdict: PASS or FAIL** (FAIL if any High impact finding)

---

### Pass 3: Standard Code Review

**Load expertise**: Read `.polaris/skills/superpowers/standard-reviewer.md` if it exists in the project root. If not found, read the built-in prompt at `src/specify_cli/superpowers/prompts/standard-reviewer.md`. Use its focus areas, quality checklist, and common pitfalls as your review criteria for this pass. If neither file is found, use the checklist below as fallback.

Review ALL changed code for general quality:

1. **Code style** - Consistent naming, formatting, project conventions followed
2. **Test coverage** - New/changed code has corresponding tests, edge cases covered
3. **Error handling** - Happy path AND error paths handled, meaningful error messages
4. **Documentation** - Changed public APIs have updated docs, non-obvious logic has comments
5. **Backward compatibility** - No breaking changes to public interfaces without migration path

For each finding, report: file, line number, issue type, and suggested fix.

**Pass 3 Verdict: PASS or FAIL** (FAIL if tests missing for new code or breaking changes undocumented)

---

### Review Summary

After completing all 3 passes, you MUST output this summary table:

```
## Review Summary

| Pass | Verdict | Required | Blocking |
|------|---------|----------|----------|
| Security | PASS/FAIL | yes/no | yes/no |
| Performance | PASS/FAIL | yes/no | yes/no |
| Standard | PASS/FAIL | yes/no | yes/no |

**Overall: APPROVED / REJECTED**
```

- **APPROVED**: All required passes are PASS (advisory FAIL passes noted as warnings)
- **REJECTED**: Any required pass is FAIL

If REJECTED, list items to fix grouped by pass under "### Items to Fix".

## Step 6: Complete the review

Use the overall verdict (APPROVED/REJECTED) to decide which command to run:

- **APPROVED**: `polaris agent tasks move-task WP## --to done --note "Review passed: <summary>"`
- **REJECTED**: Write feedback to a temp file, then run `polaris agent tasks move-task WP## --to planned --review-feedback-file <temp-file-path>`

**Telemetry**: Run: `polaris telemetry record review --feature <slug> --phase complete --agent {{AGENT_NAME}} --wp <WP_ID>`
