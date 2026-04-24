---
description: Create or update the project constitution through interactive phase-based discovery.
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## What This Command Does

Creates or updates `.polaris/memory/constitution.md` through interactive 4-phase discovery.

**Constitution is OPTIONAL.** All polaris commands work without it. It captures: technical standards, code quality expectations, tribal knowledge, and governance rules.

## Discovery Phases

| Phase | Content | Questions | Required? |
|-------|---------|-----------|-----------|
| 1. Technical Standards | Languages, testing, performance, deployment | 4-5 | Recommended |
| 2. Code Quality | PR rules, review checklist, quality gates, docs | 3-4 | Optional |
| 3. Tribal Knowledge | Conventions, lessons learned, historical decisions | 2-4 | Optional |
| 4. Governance | Amendment process, compliance, exceptions | 2-3 | Optional |

**Paths**: Minimal (~1 page, Phase 1 only, 3-5 questions) or Comprehensive (~2-3 pages, all phases, 8-12 questions).

## Steps

### 1. Initial Choice

Ask: A) Skip (create placeholder), B) Minimal (Phase 1 only), C) Comprehensive (all phases)

If skipped: write placeholder to `.polaris/memory/constitution.md` and exit.

### 2. Phase 1 - Technical Standards

Ask one at a time with examples:
- **Q1 Languages/Frameworks**: e.g., "Python 3.11+ with FastAPI", "TypeScript 4.9+ with React 18"
- **Q2 Testing**: e.g., "pytest with 80% coverage", "Jest with 90% coverage"
- **Q3 Performance/Scale**: e.g., "1000 req/s at p95 < 200ms", "N/A"
- **Q4 Deployment**: e.g., "Docker on K8s", "Cross-platform: Linux/macOS/Windows"
- **Q5 Azure DevOps**: Ask if they use ADO for work items. If yes, collect org URL and project name. Enables `/polaris.fix` ADO integration.

### 3. Phase 2 - Code Quality (comprehensive only)

Ask to skip or continue. If yes:
- **PR Requirements**: approval count, CI checks
- **Review Checklist**: what reviewers should check
- **Quality Gates**: what must pass before merge
- **Documentation Standards**: docstrings, README, ADRs

### 4. Phase 3 - Tribal Knowledge (comprehensive only)

Ask to skip or continue. If yes:
- **Team Conventions**: coding styles, patterns to follow
- **Lessons Learned**: past mistakes to avoid
- **Historical Decisions** (optional): architectural choices and rationale

### 5. Phase 4 - Governance (comprehensive only)

Ask to skip or continue. If skipped, use defaults: PR-based amendments, reviewer compliance, case-by-case exceptions.

If yes: amendment process, compliance validation, exception handling (optional).

### 6. Summary and Confirmation

Present summary of all phases/answers. Ask: A) Write it, B) Start over, C) Cancel.

### 7. Write Constitution File

Generate markdown to `.polaris/memory/constitution.md` with sections for each completed phase. Include:
- Header with project name, date, version
- Technical Standards (Q1-Q4)
- Azure DevOps section (if Q5 answered yes)
- Code Quality (if Phase 2)
- Tribal Knowledge (if Phase 3)
- Governance (Phase 4 or defaults)
- License Compliance section (always included):
  - Allowed: Apache-2.0, BSD-2/3-Clause, MIT, ISC, PSF-2.0, Unlicense, 0BSD, CC0-1.0
  - Prohibited: LGPL, AGPL, GPL, SSPL, BSL, CPAL, EUPL, MPL-2.0

### 8. Success Message

Report: file location, phases completed, next steps (review, share, run /polaris.specify).

## Behaviors

- Ask one question at a time with skip options
- Keep constitution lean (1-3 pages)
- If skipped entirely, still create placeholder file
