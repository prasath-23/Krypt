---
description: Restructure a codebase to follow organization monorepo standards and best practices.
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Goal

Analyze the current project structure against organization best practices (monorepo layout, CalVer versioning, health endpoints, documentation) and guide the user through restructuring with a non-destructive migration plan.

## Execution Steps

### 1. Analyze Current Structure

Examine the project layout and identify:

- **Source code location**: Where does code live? (src/, lib/, app/, root-level?)
- **Test location**: Where are tests? (tests/, __tests__/, spec/, alongside source?)
- **Documentation**: Is there docs/? README quality?
- **CI/CD**: Any workflows? (.github/workflows/, .gitlab-ci.yml, azure-pipelines.yml)
- **Containerization**: Dockerfile? docker-compose.yml?
- **Versioning**: How is the version tracked? (package.json, pyproject.toml, VERSION file?)
- **Configuration**: Environment management? (.env, config files)

### 2. Gap Analysis Report

Compare against the organization standard monorepo layout:

```
Expected structure:
  src/           - Application source code
  tests/         - Test suite
  docs/          - Documentation
  .github/       - CI/CD workflows
  docker/        - Docker configurations (optional)
  VERSION        - CalVer version file
  CHANGELOG.md   - Change log
  README.md      - Project documentation
  Dockerfile     - Container definition
```

Generate a gap report:

```
Structure Gap Analysis
======================

Current vs Standard Layout:

  [PASS] src/ directory exists
  [FAIL] No VERSION file (CalVer not configured)
  [FAIL] No CHANGELOG.md
  [WARN] Tests in src/__tests__/ instead of tests/
  [PASS] Dockerfile exists
  [FAIL] No health endpoints detected
  [WARN] No .editorconfig

Total: 3 passed, 3 failed, 2 warnings
```

### 3. Migration Plan

Propose specific changes, grouped by priority:

**High priority** (structural):
- Move test files to standard location
- Create VERSION file with CalVer format
- Add CHANGELOG.md

**Medium priority** (operational):
- Add health endpoints
- Add or update CI/CD workflows
- Add .editorconfig

**Low priority** (nice-to-have):
- Reorganize documentation
- Add missing Docker configurations

### 4. Interactive Confirmation

For each proposed change, ask:

> Apply this change?
> - Move tests/__tests__/ to tests/ (y/n)
> - Create VERSION file with CalVer (y/n)
> - Add CHANGELOG.md template (y/n)

**Never delete files without explicit confirmation.** Offer to move rather than delete.

### 5. Apply Approved Changes

Execute only the changes the user approved. For file moves:

```bash
# Always use git mv for tracked files
git mv old/path new/path

# Update imports/references after moves
# Show the user what imports changed
```

### 6. Post-Restructure Validation

1. Run existing tests to verify nothing broke
2. Verify import paths are updated
3. Show a before/after structure comparison

### 7. Summary

```
Restructure Complete!

Changes applied:
  - Moved tests to tests/
  - Created VERSION (2026.02.0)
  - Added CHANGELOG.md
  - Added .editorconfig

Skipped (user declined):
  - Health endpoints
  - CI/CD workflows

Run tests to verify: <detected test command>
```

## Operating Principles

- **Non-destructive**: Never delete files without explicit confirmation
- **Git-aware**: Use `git mv` for tracked files to preserve history
- **Incremental**: Apply changes one at a time, verify after each
- **Preserve behavior**: Restructuring should not change how the code runs
- **Show diffs**: Display what will change before applying

## Context

{ARGS}
