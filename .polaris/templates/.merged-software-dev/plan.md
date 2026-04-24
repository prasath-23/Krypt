---
description: Execute the implementation planning workflow using the plan template to generate design artifacts.
scripts:
  sh: polaris agent feature setup-plan --json
  ps: polaris agent feature setup-plan -Json
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Location Check (0.11.0+)

This command runs in the **main repo**, not in a worktree. NO worktrees are created during planning.

- Verify you are on the target branch before scaffolding plan.md
- Planning artifacts live in `polaris-specs/###-feature/`
- The plan is committed to the target branch after generation

**Path reference rule:** Provide either absolute paths or paths relative to the project root (e.g., `polaris-specs/<feature>/tasks/`). Never refer to a folder by name alone.

## Planning Interrogation (mandatory)

Before executing any scripts or generating artifacts you must interrogate the specification and stakeholders.

- **Scope proportionality (CRITICAL)**: Assess the feature's complexity from the spec:
  - **Trivial/Test Features**: Ask 1-2 questions maximum, then proceed with sensible defaults
  - **Simple Features**: Ask 2-3 questions about tech choices and constraints
  - **Complex Features**: Ask 3-5 questions covering architecture, NFRs, integrations
  - **Platform/Critical Features**: Full interrogation with 5+ questions

- **User signals to reduce questioning**: If the user says "use defaults", "just make it simple", "skip to implementation" - minimize planning questions and use standard approaches.

- **Conversational cadence**: After each reply, assess if you have SUFFICIENT context for this feature's scope.

Planning requirements (scale to complexity):

1. Maintain a **Planning Questions** table internally. Do **not** render this table to the user.
2. When you have sufficient context, summarize into an **Engineering Alignment** note and confirm.

## Outline

1. **Check planning discovery status**:
   - If any planning questions remain unanswered, stay in the one-question cadence and end with `WAITING_FOR_PLANNING_INPUT`.
   - Once confirmed by the user, continue.

2. **Setup**: Run `{SCRIPT}` from repo root and parse JSON for FEATURE_SPEC, IMPL_PLAN, SPECS_DIR.

3. **Load context**: Read FEATURE_SPEC and `.polaris/memory/constitution.md` if it exists.

4. **Execute plan workflow**: Follow the structure in IMPL_PLAN template:
   - Phase 0: Generate research.md
   - Phase 1: Generate data-model.md, contracts/, quickstart.md

5. **STOP and report**: Report plan.md path, generated artifacts, and suggest `/polaris.tasks`.

## Key rules

- Use absolute paths
- ERROR on gate failures or unresolved clarifications
- Planning happens in main repo - NO worktrees created
- Commit plan artifacts to polaris-specs/###-feature/
