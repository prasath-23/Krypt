---
description: Set up CI/CD pipelines, Docker, Helm charts, and deployment configuration.
---


## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Quick Mode

If user passes `--quick` or arguments contain "quick": Skip discovery questions. Auto-detect language/framework. Use defaults: GitHub Actions CI/CD, Kubernetes deployment, all environments (dev/staging/prod). Generate Dockerfile, workflows, Helm charts, and .env.example immediately.

## Goal

Set up a complete DevOps pipeline following organization standards. Generates CI/CD workflows, Dockerfiles, container orchestration configs, and environment management.

## Steps

### 1. Discovery

Detect project context:
- **Language/framework**: Scan for package.json, pyproject.toml, go.mod, Cargo.toml, etc.
- **Existing CI/CD**: Check .github/workflows/, .gitlab-ci.yml, azure-pipelines.yml
- **Existing Docker**: Check Dockerfile, docker-compose.yml
- **Test framework**: Detect test runner

Ask the user:
- **CI/CD platform**: GitHub Actions (default), Azure DevOps, GitLab CI
- **Deployment target**: Container registry, Kubernetes, cloud service, or none (CI-only)
- **Environments**: dev, staging, production
- **Container registry**: ghcr.io, Docker Hub, ACR, ECR (if deploying containers)

### 2. Generate CI/CD Pipeline

Based on selected platform, generate workflow files:

**GitHub Actions** (.github/workflows/): `ci.yml` (lint+test+build on PR), `release.yml` (container build+push on tag), `deploy.yml` (K8s deployment)

**Azure DevOps** (azure-pipelines.yml): Build pipeline with stages: lint, test, build, deploy

**GitLab CI** (.gitlab-ci.yml): Pipeline with stages: lint, test, build, deploy

Each pipeline includes: language-appropriate build, test execution with coverage, container image build/push, environment-specific deployment gates.

### 3. Generate Dockerfile

If no Dockerfile exists, create one with:
- Multi-stage build (separate build and runtime stages)
- Non-root user for security
- HEALTHCHECK instruction
- Standard OCI labels
- Language-specific optimizations: Python=slim base+requirements caching, Node=alpine+package.json caching, Go=static binary+scratch/distroless, C#=SDK build+aspnet runtime

### 4. Generate Docker Compose

If deploying with docker-compose, generate standard service config with build, ports, env_file, and healthcheck.

### 5. Generate Helm Charts (if Kubernetes)

Create complete Helm chart structure under `helm/`:

```
helm/
  Chart.yaml              # apiVersion v2, app metadata
  values.yaml             # Base: replicaCount=1, image config, service ClusterIP:80->8080,
                          # resources (100m-500m CPU, 128Mi-512Mi mem), liveness/readiness probes
  values-staging.yaml     # replicaCount=2, ingress enabled, staging domain
  values-production.yaml  # replicaCount=3, ingress+TLS, autoscaling 3-10 replicas, 500m-1000m CPU
  .polaris/templates/
    _helpers.tpl          # Standard name, fullname, labels, selectorLabels helpers
    deployment.yaml       # Standard Deployment with all values references
    service.yaml          # ClusterIP service
    ingress.yaml          # Conditional ingress with TLS support
    hpa.yaml              # Conditional HPA with CPU/memory targets
    serviceaccount.yaml   # Conditional ServiceAccount
    configmap.yaml        # Optional ConfigMap
    secret.yaml           # Optional Secret
```

Use standard Helm chart patterns. All templates must reference values via `{{ .Values.* }}` and use the helper functions from `_helpers.tpl`. Security: `runAsNonRoot: true, runAsUser: 1000`.

### 5b. Generate CD Pipeline

Generate deployment pipeline for the selected CI/CD platform that deploys to Kubernetes using Helm:
- **GitHub Actions**: `deploy.yml` with staging (on release) and production (manual dispatch) jobs using `azure/setup-helm@v3` and `azure/k8s-set-context@v3`
- **Azure DevOps**: `azure-pipelines-deploy.yml` with parameterized environment and version, using `HelmInstaller@1` and `Kubernetes@1` tasks
- **GitLab CI**: Deploy stages using `alpine/helm:3.13.0` image with kubeconfig from secrets

Each pipeline: `helm upgrade --install` with namespace per environment, values files overlay, image tag override, `--wait --timeout 5m`, followed by `kubectl rollout status` verification.

### 6. Environment Configuration

Create `.env.example` with all required variables (no real values). Document environment-specific overrides.

### 7. Validation

1. Lint generated workflow files
2. Build Docker image locally: `docker build -t <project>:dev .`
3. Run container with health check
4. Verify CI config syntax

### 8. Summary

List all generated files (workflows, Dockerfile, docker-compose, .env.example, helm/), then next steps: review configs, set up repository secrets (registry creds, kubeconfig), update helm values with actual registry/domain, push to trigger CI, run `/polaris.healthcheck`.

## Principles

- Detect before generating (scan existing setup first)
- Never store secrets (only .env.example with placeholders)
- Multi-stage Docker builds (minimize size and attack surface)
- Health checks everywhere
- Ask before overwriting existing configs

Context: $ARGUMENTS
