---
description: Generate E2E tests from an Azure DevOps work item for QA engineers.
---

# /polaris.qa - E2E Test Generation from ADO Work Item

**Version**: 2026.3.4+
**Purpose**: QA-driven E2E test suite generation from ADO work items. Developer does not need Polaris.

## User Input

**Telemetry**: Run: `polaris telemetry record qa --feature <slug> --phase start --agent {{AGENT_NAME}}`

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Work Item ID Parsing

Parse work item ID from arguments. Supported formats:
- Numeric: `12345`
- Hash prefix: `#12345`
- AB prefix: `AB#12345`
- Full ADO URL: `https://dev.azure.com/{org}/{project}/_workitems/edit/12345`

Extract numeric ID. If none provided, ask:
> "Provide a work item ID. Formats: `12345`, `AB#12345`, or ADO URL."

End with `WAITING_FOR_QA_INPUT`.

## Prerequisites

### Token Pre-flight

Check `.polaris/memory/constitution.md` for configured issue tracker.

**ADO**: Verify `AZURE_DEVOPS_PAT` env var exists. Read org/project from `.polaris/config.yaml` under `ado:` or from env vars `AZURE_DEVOPS_ORG`, `AZURE_DEVOPS_PROJECT`.

If missing: show actionable error with setup instructions. End with `WAITING_FOR_QA_INPUT`.

### QA Configuration

Read `.polaris/config.yaml` for optional `qa:` section:

```yaml
qa:
  base_url: "https://staging.example.com"
  auth:
    env_username: "QA_USERNAME"
    env_password: "QA_PASSWORD"
    login_path: "/login"
```

Store values for use in discovery and generation. All optional.

## Fetch Work Item

Call ADO REST API:

```bash
python -c "
import json, os, sys, urllib.request, base64
pat = os.environ['AZURE_DEVOPS_PAT']
org_url = '{ORG_URL}'
project = '{PROJECT}'
wid = {WORK_ITEM_ID}
url = f'{org_url}/{project}/_apis/wit/workitems/{wid}?$expand=relations&api-version=7.0'
auth = base64.b64encode(f':{pat}'.encode()).decode()
req = urllib.request.Request(url, headers={'Authorization': f'Basic {auth}'})
try:
    resp = urllib.request.urlopen(req)
    data = json.loads(resp.read())
    fields = data.get('fields', {})
    relations = data.get('relations', [])
    children = [r['url'].split('/')[-1] for r in relations
                if r.get('rel') == 'System.LinkTypes.Hierarchy-Forward']
    print(json.dumps({
        'id': data['id'],
        'title': fields.get('System.Title', ''),
        'type': fields.get('System.WorkItemType', ''),
        'state': fields.get('System.State', ''),
        'description': fields.get('System.Description', ''),
        'repro_steps': fields.get('Microsoft.VSTS.TCM.ReproSteps', ''),
        'acceptance_criteria': fields.get('Microsoft.VSTS.Common.AcceptanceCriteria', ''),
        'children': children
    }, indent=2))
except urllib.error.HTTPError as e:
    print(json.dumps({'error': f'HTTP {e.code}: {e.reason}'}), file=sys.stderr)
    sys.exit(1)
"
```

**Error handling**: 404 - wrong ID/project. 401/403 - bad PAT. Network - connectivity issue.

### Hierarchy Traversal

If work item has children (from relations):
1. Fetch each child work item using same API pattern
2. Aggregate acceptance criteria from parent + all children
3. Group by child for test scenario organization
4. Skip failed child fetches (warn, continue)

If no children: proceed with single work item.

## Auto-Amend Detection

Scan `polaris-specs/` for directories matching `qa-{id}-*`.

**If found** (amend mode):
1. Re-fetch ADO work item (criteria may have changed)
2. Read existing `spec.md`, diff current ADO criteria against it
3. For each affected `.e2e.js` file:
   - Check `git diff` for manual QA edits since last generation
   - If edited AND criteria changed: present per-file choice (keep manual / accept regenerated / merge)
   - If unedited: regenerate silently
4. Update spec.md, regenerate affected tests
5. Append amendment entry to meta.json
6. Post ADO comment: "E2E tests updated. {N} scenarios modified, {M} unchanged."
7. Skip to Summary.

**If not found**: continue with creation flow.

## QA Discovery

Scale questions to work item richness (2-4 questions):

1. **Base URL**: Target environment URL. Suggest from `qa.base_url` config if available.
2. **Auth**: Does app require login? Roles to test? Credentials via env vars (default: `QA_USERNAME`, `QA_PASSWORD`).
3. **Test data**: Specific records, users, or states needed?
4. **Existing tests**: Any tests to avoid duplicating?

If acceptance criteria are detailed: minimize to 1-2 confirmations.

Present all questions as numbered list. End with `WAITING_FOR_QA_INPUT`.

### Criteria Gap-Fill (Non-skippable)

If acceptance criteria are sparse or missing:
1. Infer likely criteria from title, description, and work item type
2. Present suggested criteria as numbered list
3. QA confirms, adjusts, or adds
4. Record enriched criteria (attributed as "QA-enriched, not from ADO")

This step is a core part of the QA value proposition.

## Generate Artifacts

### Create Feature Directory

```bash
polaris agent feature create-feature "qa-{id}-{kebab-title}" --json
```

Parse: feature_dir, target_branch. Slug max 50 chars.

### meta.json

Write to `{feature_dir}/meta.json`:

```json
{
  "slug": "qa-{id}-{kebab-title}",
  "friendly_name": "QA: {title}",
  "mission": "qa-testing",
  "ado_work_item": {"id": 0, "type": "{type}", "title": "{title}", "url": "{url}"},
  "created_at": "<ISO>",
  "target_branch": "<current-branch>",
  "vcs": "git",
  "qa_discovery": {
    "base_url": "<from discovery>",
    "auth_required": false,
    "roles": [],
    "test_data": "<notes>"
  }
}
```

### spec.md (Testability-Focused)

Write `{feature_dir}/spec.md` with:
- Overview: what is being tested, ADO work item link
- Acceptance Criteria: from ADO + QA enrichments (with source attribution)
- Test Scenarios: derived from criteria, grouped by theme
- Auth Requirements: if applicable
- Data Prerequisites: test data needs
- Out of Scope: what this test suite does NOT cover

### test-plan.md

Write `{feature_dir}/test-plan.md`:
- Scenario table: ID, description, type (happy/error/edge), priority
- Data requirements per scenario
- Auth matrix (roles x scenarios)

### Work Packages + .e2e.js Files

Group scenarios into WPs (by theme: happy path, error handling, edge cases, etc.).

For each WP, create:
1. `{feature_dir}/tasks/WP##-{group}.md` with frontmatter (work_package_id, lane: planned, ado_criteria)
2. `{feature_dir}/tests/e2e/WP##-{group}.e2e.js` with complete Playwright tests

**.e2e.js format**:

```javascript
import { test, expect } from '@playwright/test';

test.describe('WP01: {group}', () => {
  test('{scenario}', async ({ page }) => {
    // Navigate to {base_url}{path}
    // Fill {field} with process.env.QA_USERNAME
    // Click the {button} button
    // Verify page shows "{expected text}"
    // Verify no JavaScript errors
  });
});
```

CRITICAL: Never write actual credential values. Always use `process.env.{VAR_NAME}`.

### Gitignore Check

Verify `.gitignore` includes `.env` files. If not, add entry or warn.

## ADO Write-back

Post comment on work item via ADO REST API:
"E2E test suite created by QA via Polaris. {N} test scenarios across {M} work packages. Spec: polaris-specs/qa-{id}-{title}/"

Non-blocking: warn on failure, continue.

## Commit

```bash
git add polaris-specs/qa-{id}-{kebab-title}/
git commit -m "test(qa): generate E2E test suite for ADO#{id} - {title}"
```

## Summary

Display:

```
E2E Test Suite Generated: AB#{id} - {title}
  Artifacts: spec.md, test-plan.md, {N} WPs, {M} .e2e.js files
  Scenarios: {total} ({happy} happy, {error} error, {edge} edge)
  Location:  polaris-specs/qa-{id}-{kebab-title}/

Next steps:
  1. Review generated tests in polaris-specs/qa-{id}-{kebab-title}/tests/e2e/
  2. Run /polaris.runtests to execute the test suite
  3. Push and create PR when satisfied
```

**Files to commit vs gitignore**: If untracked `.polaris/` files exist:
- **Commit**: polaris-specs/ entries, .polaris/config.yaml, .polaris/memory/
- **Gitignore**: .polaris/.dashboard, .polaris/telemetry/, .polaris/autopilot-state.json

## Cross-Platform Notes

All commands used here are Polaris CLI commands, Python one-liners, or git operations that work on Windows, macOS, and Linux. No shell-specific commands are used.

**Telemetry**: Run: `polaris telemetry record qa --feature <slug> --phase complete --agent {{AGENT_NAME}}`
