---
description: Scaffold a new application from organization monorepo template with guided setup.
---


## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Quick Mode

If user passes `--quick` or arguments contain "quick": Skip all discovery questions. Use Aptean defaults: fullstack app, Aptean branding yes, AKS deployment. Require only project name (from arguments or ask once). Scaffold immediately with defaults.

## Goal

Scaffold a new application following the organization monorepo template. Uses the Aptean standard tech stack by default: Django 6+ (Python 3.14+) backend, Vite / Next 16+ / React 19+ frontend, PostgreSQL 17+. Supports frontend, backend, worker, MCP server, and full-stack application types.

## Execution Steps

### 1. Discovery

Ask the user about their project requirements:

- **Application type**: frontend, backend, worker, mcp, or fullstack
- **Project name**: kebab-case identifier (e.g., `my-new-service`)
- **Description**: Brief one-liner for README and package metadata
- **Aptean branding**: Should AppCentral design system be applied? (default: yes)
  - Yes: Aptean dark theme, Suisse Intl typography, teal accent, `--aptean-*` CSS variables
  - No: neutral default theme
- **Deployment target**:
  - Kubernetes (AKS) for AppCentral (default) - Helm charts, AKS manifests, ACR registry
  - Standalone / feature app - Docker Compose only, no K8s

The default stack is **Django 6+ (Python 3.14+) backend, Vite / Next 16+ / React 19+ frontend, PostgreSQL 17+**. Do NOT ask the user to choose a language or framework - these are the Aptean standard. If the user explicitly requests a different stack in their arguments, honor it but note it deviates from the Aptean standard.

If the user provided arguments, extract answers from there first.

### 2. Scaffold from Template

**Online mode** (preferred): Clone the organization template repository:

```bash
git clone --depth 1 https://github.com/Shared-Technology-Group/workspaces-sdd-repo-template.git <project-name>
cd <project-name>
python -c "import shutil; shutil.rmtree('.git')"
git init --initial-branch main
```

**Offline mode** (fallback): Generate the standard monorepo structure:

```
<project-name>/
  src/
    <app-type>/
  tests/
  docs/
  .github/
    workflows/
      ci.yml
  docker-compose.yml
  Dockerfile
  README.md
  VERSION
  CHANGELOG.md
```

### 3. Configure for Default Stack

Set up the Aptean standard stack:

- **Backend**: `pyproject.toml` (Django 6+, Python 3.14+), `manage.py`, Django settings module, virtual environment setup
- **Frontend**: `package.json`, `tsconfig.json`, Vite or Next.js 16+ config with React 19+
- **Database**: PostgreSQL 17+ connection in Django settings, initial migration
- **Docker**: multi-stage `Dockerfile`, `docker-compose.yml` with PostgreSQL service

If the user explicitly requested a non-default stack, use the appropriate language patterns instead (Python: pyproject.toml; TypeScript/JS: package.json, tsconfig.json; Go: go.mod; Rust: Cargo.toml; C#: .csproj; Java: pom.xml or build.gradle).

### 4. Initialize Polaris

```bash
polaris init --here --merge
```

### 4a. Apply Aptean Branding (if selected)

If the user selected Aptean branding (default: yes):

- Copy Suisse Intl fonts to `static/fonts/` (backend) or `public/fonts/` (frontend)
- Generate base CSS with `--aptean-*` design tokens (dark theme, teal accent `#54B3BE`, Suisse Intl typography)
- Apply dark-theme-first color system
- Add Aptean logo SVG to static assets
- Reference `aptean-style/SKILL.md` for the full token set

### 4b. Configure Deployment Target

**Kubernetes (AKS) for AppCentral** (default):

- Create `helm/` directory with:
  - `Chart.yaml`, `values.yaml`, `values-staging.yaml`, `values-production.yaml`
  - Templates: `deployment.yaml`, `service.yaml`, `ingress.yaml`, `hpa.yaml`
  - Health probes (liveness, readiness, startup)
- Create `.github/workflows/deploy.yml` for AKS deployment
- Default container registry: Azure Container Registry (ACR)

**Standalone / feature app**:

- Create `docker-compose.yml` only (no Helm or K8s manifests)
- Create `.github/workflows/ci.yml` for test + build
- No Kubernetes resources

### 5. Post-Scaffold Validation

1. Install dependencies using detected package manager
2. Run initial test suite to verify scaffold works
3. Create initial git commit (include `Co-Authored-By: Aptean Polaris <polaris@aptean.com>` trailer)

### 6. Summary

Display what was created and suggest next steps:

```
Application scaffolded!

  Type:       <app-type>
  Stack:      Django 6+ / Vite+React 19+ / PostgreSQL 17+
  Branding:   Aptean AppCentral (yes/no)
  Deployment: AKS for AppCentral / Standalone
  Location:   <project-name>/

Next steps:
  1. cd <project-name>
  2. Review generated structure
  3. /polaris.specify to define your first feature
```

## Operating Principles

- **Ask before acting**: Confirm choices before generating files
- **Detect connectivity**: Try online template first, fall back to offline patterns
- **CalVer versioning**: Initialize VERSION file with CalVer format (YYYY.MM.PATCH)
- **Never overwrite**: If target directory has existing files, warn and confirm

## Context

$ARGUMENTS
