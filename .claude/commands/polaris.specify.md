---
description: Create or update the feature specification from a natural language feature description.
---

## User Input

**Telemetry**: Run: `polaris telemetry record specify --feature <slug> --phase start --agent {{AGENT_NAME}}`


```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Quick Mode

If user passes `--quick` or arguments contain "quick": Skip discovery gate. Assume simple complexity. Ask only: (1) What is the feature? (2) Key acceptance criteria. Generate spec directly with no clarification rounds, no separate plan phase - produce spec.md and plan.md together. Commit and report.

## Working Directory

Run from planning repository root. NO worktrees created. All output goes to `polaris-specs/###-feature/` and is committed to the target branch. Worktrees are created later via `polaris implement WP##`.

Verify: if `pwd` contains `.worktrees/`, STOP. Planning runs from the main repo only. Navigate back to repo root first.

## Discovery Gate

Conduct a structured discovery interview scaled to complexity:

- **Trivial** (hello world, simple page): 1-2 questions max, then proceed
- **Simple** (small UI, minor enhancement): 2-3 questions
- **Complex** (new subsystem, integration): 3-5 questions
- **Critical** (auth, payments, infra): 5+ questions

Rules:
- Determine the full question set based on complexity tier, then present ALL questions as a **numbered list** in a single message
- End with `WAITING_FOR_DISCOVERY_INPUT`
- Developer responds with numbered answers (any order)
- If user says "just testing" or "skip questions" - minimize and use defaults
- Track questions internally (do not render table to user)
- When sufficient context gathered, present **Intent Summary** and confirm
- Empty invocation: stay in interview mode until description agreed

**Work Item Question**: Always include as one of the batch questions:
- "Is this work linked to a tracker item? (ADO: AB#12345, GitHub: #42 or URL, Jira: PROJ-123, or 'skip')"
- Auto-detect provider from input format:
  - `AB#` prefix or `dev.azure.com` URL -> ADO: fetch via ADO REST API, confirm title
  - `#` prefix or `github.com` URL -> GitHub: store number and URL
  - `PROJ-123` pattern or Jira URL -> Jira: store key and URL
- If skipped or credentials missing: proceed without link (never blocks)

**Estimation Question**: Always include as one of the batch questions (after ADO question):
- "What is the team's estimate for this work without AI assistance? (e.g., '3 days', '16 hours', '5 SP')"
- If the ADO work item has an Original Estimate or Story Points field: present it and ask to confirm or adjust
- If no ADO estimate: ask the developer to provide one
- Normalize to hours (1 day = 8h, 1 week = 40h, 1 SP = 4h; configurable in `.polaris/config.yaml` under `estimation:`)
- Store in meta.json under `estimation` field:
  ```json
  {"estimation": {"baseline_raw": "3 days", "baseline_hours": 24, "source": "developer|ado|ado-adjusted", "captured_at": "<ISO>"}}
  ```
- If skipped: proceed without estimation data (never blocks)

**Partial answer handling**: If the developer answers fewer than N questions:
1. Identify unanswered questions by number
2. Re-present only the unanswered questions (max 2 re-ask rounds)
3. After 2 rounds, use informed defaults for remaining questions
4. Document any defaulted answers in the spec's Assumptions section

## Mission Selection

After discovery, determine mission:
- **software-dev**: Building features, APIs, tools, apps (build/implement/create)
- **research**: Investigations, analysis, evaluations (research/investigate/analyze)

Confirm with user unless explicit. If `--mission <key>` provided, use it directly.

## Workflow

**IMPORTANT - Write early, write often.** Context windows can drop mid-conversation.
Create the feature directory and write files as soon as you have content - do not
accumulate everything in conversation memory. Every file write is a checkpoint that
survives context loss.

1. **Check discovery status** - stay in question loop until Intent Summary confirmed

2. **Create feature** (once discovery complete, title and mission confirmed):
   ```bash
   polaris agent feature create-feature "<slug>" --json
   ```
   Parse JSON for `feature`, `feature_dir`, `target_branch`. Run this ONCE only.

3. **Create meta.json** in feature dir:
   ```json
   {
     "feature_number": "<number>", "slug": "<full-slug>",
     "friendly_name": "<Title>", "mission": "<mission>",
     "source_description": "$ARGUMENTS",
     "created_at": "<ISO>", "target_branch": "<current-branch>", "vcs": "git",
     "ado_work_item": {"id": 12345, "type": "User Story", "title": "...", "url": "...", "parent_id": null},
     "github_issue": {"number": 42, "title": "...", "url": "..."},
     "jira_issue": {"key": "PROJ-123", "title": "...", "url": "..."}
   }
   ```
   Use the CURRENT branch for `target_branch` (from create-feature JSON output).
   Include only the detected provider's field. Omit others entirely.

   **Write discovery notes now:** Save `<feature_dir>/discovery-notes.md` with the
   Intent Summary and all Q&A answers collected so far. This protects against context
   loss during spec generation. Delete this file after spec.md is finalized.

4. **Generate spec** from discovery answers (not raw $ARGUMENTS):
   - Identify actors, actions, data, constraints, success metrics
   - For ambiguity: ask user (max 3 `[NEEDS CLARIFICATION]` markers for truly deferred decisions)
   - Fill: User Scenarios, Functional Requirements (testable), Success Criteria (measurable, tech-agnostic), Key Entities
   - **Write to `<feature_dir>/spec.md` immediately** - do not wait for later steps.
     A partial spec on disk is better than a perfect spec lost to context.

5. **Control map** (if feature has 2+ interrelated flows/forms/screens):
   Create `<feature_dir>/control-map.md`:
   ```markdown
   ## Flows
   | Flow | Purpose | Key Files |
   |------|---------|-----------|
   | <name> | <purpose> | <comma-separated paths> |

   ## Shared Dependencies
   | Component | Used By | Path |
   |-----------|---------|------|
   | <name> | <flow1>, <flow2> | <path> |
   ```
   Target: under 100 lines. Skip if single-flow feature.

6. **Validate spec** against quality checklist:
   - No implementation details, focused on user value, all sections complete
   - Requirements testable, success criteria measurable and tech-agnostic
   - If items fail: fix spec.md on disk and re-validate (max 3 iterations)
   - If `[NEEDS CLARIFICATION]` remains: present options (A/B/C/Custom) for each, update spec.md with answers
   - Save checklist to `<feature_dir>/checklists/requirements.md`

7. **Auto-review**: Re-read spec end-to-end, identify gaps, ask user if needed, update spec.md on disk.
   Delete `<feature_dir>/discovery-notes.md` once spec is finalized.

## Phase 2: Implementation Planning

Proceed directly to planning (eliminates separate `/polaris.plan` step).

**Planning interrogation** - scaled to complexity (same tiers as discovery):
- Suggest best option and confirm rather than open-ended questions
- Ask one at a time, end with `WAITING_FOR_PLANNING_INPUT`
- Summarize into **Engineering Alignment** note and confirm

**Plan generation**:
1. Run `polaris agent feature setup-plan --feature <feature-slug> --json`
2. Read spec and `.polaris/memory/constitution.md` (if exists)
3. Update Technical Context, Constitution Check (if applicable), generate research.md (if unknowns), data-model.md, contracts/, quickstart.md
4. Commit planning artifacts

## Spec Guidelines

- Focus on **WHAT** and **WHY**, never HOW (no tech stack, APIs, code structure)
- Written for business stakeholders
- Mandatory sections must be completed; remove irrelevant optional sections entirely
- Make informed guesses using industry standards; document in Assumptions
- Success criteria: measurable, tech-agnostic, user-focused, verifiable

## On Completion

- If `--no-continue`: STOP and report spec path
- If spec commit fails, do NOT auto-progress
- Default: ask "Spec and plan are ready. Proceed with autopilot? (y/n)"
  - **y** (default): Launch `/polaris.autopilot` with the current feature
  - **n**: Stop and report spec path for manual `/polaris.tasks` or `/polaris.plan`


**Telemetry**: Run: `polaris telemetry record specify --feature <slug> --phase complete --agent {{AGENT_NAME}}`
