---
name: DevOps Pipeline Skill
description: |
  Governs CI/CD pipeline and Docker build invariants.
---

  ----------------------------------------------------------------------
  🔒 PATH CONTROL RULES
  ----------------------------------------------------------------------

  manage-appConfig-secrets/**
    - Structure MUST NOT change
    - Existing values MUST NOT be removed
  manage-appConfig-secrets/azureConfig/**
    - Agent MUST inspect the target repository for:
        - Environment variables
        - Application configuration usage
        - Known config keys referenced in code or manifests
    - Agent MUST add missing keys to appconfig and keyvault files
    - Agent MUST NOT remove or rename existing keys
    - If the required value cannot be inferred:
        - Insert the key with the existing default value
        - OR leave the placeholder value unchanged

  ----------------------------------------------------------------------
  ✔ MIGRATION-AWARE HELM HOOK ANNOTATIONS (CONDITIONAL)
  ----------------------------------------------------------------------

  If migration job is included in the deployment:

  The agent MUST add Helm hook annotations to the following sections
  in all azureConfig files AND bgmConfig files present inside manage-appConfig-secrets folder:

  1. configs[] section in appconfig*.yml files:
     annotations:
       helm.sh/hook: pre-install,pre-upgrade
       helm.sh/hook-weight: "-5"

  2. secrets[] section in keyvault-secrets*.yml files:
     annotations:
       helm.sh/hook: pre-install,pre-upgrade
       helm.sh/hook-weight: "-10"

  3. containerRegistries[] section in keyvault-secrets*.yml files:
     annotations:
       helm.sh/hook: pre-install,pre-upgrade
       helm.sh/hook-weight: "-10"

  If migration job is NOT included:

  Keep annotations as empty objects:
     annotations: {}

  CRITICAL EXECUTION RULES:

  1. This MUST happen immediately after database detection confirms a database is present
  2. The agent MUST scan ALL files in:
     - manage-appConfig-secrets/azureConfig/appconfig-*.yml
     - manage-appConfig-secrets/azureConfig/keyvault-secrets-*.yml
     - manage-appConfig-secrets/bgmConfig/appconfig-*.yml (BGM files too)
  3. For EACH file, the agent MUST:
     - Locate the configs[] section (if present in appconfig files)
       * Add annotations under each config item
     - Locate the secrets[] section (if present in keyvault-secrets files)
       * Add annotations under each secret item
     - Locate the containerRegistries[] section (if present in keyvault-secrets files)
       * Add annotations under each containerRegistry item
       * annotation doesnot exist in the template add annotations to it
  4. Annotations MUST be indented correctly according to YAML structure
  5. The agent MUST verify annotations were added after processing:
     - Check configs[] in appconfig files
     - Check secrets[] in keyvault-secrets files
     - Check containerRegistries[] ONLY if the section has items
  6. If annotations are NOT found in configs[] or secrets[] or containerRegistries[] after database was detected → add it

  Rules:
  - Annotations MUST be added ONLY if a database is detected in the repository
  - Hook weights MUST be set as specified above
  - DO NOT modify any other fields in these sections
  - DO NOT add annotations to any other sections
  - This applies to ALL environment-specific azureConfig files
  - This applies to ALL environment-specific bgmConfig files
  - Donot add helm.sh/hook-delete-policy: before-hook-creation to the annotations
  - Make sure annotations are included in:
    - configs section in appconfig* files (both azureConfig/ and bgmConfig/)
    - secrets and containerRegistries sections in keyvault-secrets* files (azureConfig/ only)
  - bgmConfig files typically contain only appconfig files, not keyvault-secrets

  ----------------------------------------------------------------------
  ✔ APP CONFIG & SECRETS SCOPING (STRICT)
  ----------------------------------------------------------------------

  For Micro-Frontend services:
  
  - AppConfig and KeyVault entries MUST be:
      - Shared across all containers
      - Defined ONCE per environment
  
  Rules:
  
  1. The agent MUST NOT create:
       - Per-container appConfig files
       - Per-container secret definitions
  
  2. All containers MUST consume the SAME:
       - AppConfig
       - KeyVault
       - Environment variables

  ----------------------------------------------------------------------
    ✔ AUTHORITATIVE SERVICE NAMING CONTRACT (BLOCKING PHASE)
  ----------------------------------------------------------------------
    
  ServiceName, Namespace, and ReleaseName MUST be derived
  STRICTLY according to the rules in this section.
  The agent MUST NOT invent alternate naming logic.

  ServiceName:
    - The ONE identity used consistently everywhere
    - Derived from repository name
    - MUST be a SINGLE word (NO hyphens, NO underscores, NO separators)
    - MUST be between 2 and 12 characters (inclusive)
    - MUST be meaningful and immediately understandable based on the repository name
    - In MainManifest.yml `iamDetails.code`: ALL UPPERCASE (e.g., PERSASST)
    - In ALL other files: all lowercase (e.g., persasst)
    - Used for: file names, helm charts, K8s resources, routing paths,
      Key Vault names, Terraform state, IAM product codes - ALL the same name

    Derivation rules (STRICT):
    1. Analyze the repository name to understand its core purpose/domain.
    2. Split repository name on:
         - hyphens (-)
         - underscores (_)
    3. Remove ONLY generic technical filler words such as:
         - service
         - application
         - app
         - repo
         - test
         - workspace
       NOTE:
       Words that describe architecture or domain
       (e.g. micro, frontend, backend, docker)
       MUST NOT be removed blindly.
    4. From the remaining meaningful words, derive a SINGLE meaningful
       word (2-12 characters) that represents the core purpose of the repository.
       Concatenate meaningful words together (no separators) to form the name.
       Abbreviate if the concatenation exceeds 12 characters.
    5. Common naming patterns:
         - customer-management-service → custmgmt
         - employee-portal-app → employportal
         - micro-frontend-admin → microfrontend
         - inventory-backend-service → inventback
         - personal-assistant-workspace → persasst
         - notification-scheduler → notifschedlr
         - analytics-dashboard → analyticsdash (12 chars, fits)
         - order-processing-service → orderprocess (12 chars, fits)
         - adr-workspace → adrws
    6. The resulting name MUST:
         - Be immediately recognizable from the repository name
         - Be a real word, a concatenation of real words, or a well-known abbreviation
         - NOT be a random or ambiguous string
         - Be globally unique across all Aptean products
         - NOT be a generic word (assist, service, admin, portal, backend)
    7. UNIQUENESS CHECK (MANDATORY before finalizing):
         `gh search code "iamDetails" --owner Shared-Technology-Group --owner Aptean-Labs`
         If a collision is found, choose a different name.
    8. If no meaningful 2-12 character name can be derived:
         → Ask user for the name

    Key Vault naming: kv-{region}-{lower(iamDetails.code)}-{env} (e.g., kv-eus-persasst-tst)
    Total: 3 (kv-) + 4 (region-) + code (2-12) + 1 (-) + env (3-4) <= 24

  ServiceName MUST:
    - Contain only lowercase letters (uppercase ONLY in MainManifest.yml iamDetails.code)
    - Start with a letter
    - NOT contain hyphens, underscores, numbers, or any separators
    - Be DNS-label compatible
    - Be between 2 and 12 characters (inclusive)
  Namespace:
    - MUST be derived directly from ServiceName
    - MUST be lowercase
    - MUST be DNS-1123 compliant
    - MUST NOT exceed 63 characters
    Derivation rules (STRICT):
    1. Start with ServiceName.
    2. Since ServiceName no longer contains hyphens or suffixes,
       Namespace is typically IDENTICAL to ServiceName.
    3. Validate Namespace against Kubernetes DNS-1123 rules:
         - lowercase
         - alphanumeric and hyphens only
         - starts with a letter
         - ends with letter or number
    4. If Namespace violates constraints:
         → STOP with clear error
  ReleaseName:
    - Generated by agent
    - Format: <adjective>-<noun>
    - Deterministic per execution
  
  BGM Configuration Release Names (CRITICAL):
  
  For bgmConfig files (manage-appConfig-secrets/bgmConfig/):
  - TWO different release names are required
  - Each setName (one, two) MUST have a UNIQUE release name
  - The agent MUST generate TWO distinct release names:
      1. Primary ReleaseName (for setName: one)
      2. Secondary ReleaseName (for setName: two)
  
  Generation rules:
  - Primary: {{ .ReleaseName }} (the standard generated release name)
  - Secondary: {{ .ReleaseNameTwo }} (a DIFFERENT adjective-noun combination)
  - Both MUST follow the same format rules
  - Both MUST be deterministic
  - Both MUST be different from each other
  
  Token replacement in bgmConfig files:
  - {{ .ReleaseName }} → Primary release name (e.g., brave-eagle)
  - {{ .ReleaseNameTwo }} → Secondary release name (e.g., swift-falcon)
  - The agent MUST replace BOTH tokens with DIFFERENT values
  
  In bgmConfig templates:
  - Service with setName: one → uses {{ .ReleaseName }}
  - Service with setName: two → uses {{ .ReleaseNameTwo }}
  
  Example:
  If Primary ReleaseName = brave-eagle
  Then Secondary ReleaseName = swift-falcon (or any other valid combination)
  
  services:
  - name: {{ .ReleaseName }}-{{ .ServiceName }}-svc-{{ .ServiceName }}
    releaseName: {{ .ReleaseName }}
    setName: one
  - name: {{ .ReleaseNameTwo }}-{{ .ServiceName }}-svc-{{ .ServiceName }}
    releaseName: {{ .ReleaseNameTwo }}
    setName: two
  
  After token replacement:
  - name: brave-eagle-{{ .ServiceName }}-svc-{{ .ServiceName }}
    releaseName: brave-eagle
    setName: one
  - name: swift-falcon-{{ .ServiceName }}-svc-{{ .ServiceName }}
    releaseName: swift-falcon
    setName: two

  ----------------------------------------------------------------------
  ✔ TOKEN REPLACEMENT (STRICT + TRANSFORMS)
  ----------------------------------------------------------------------

  Base tokens (ALWAYS supported):
    - {{ .ServiceName }} (also matches {{.ServiceName}} without spaces)
    - {{ .Namespace }} (also matches {{.Namespace}} without spaces)
    - {{ .ReleaseName }} (also matches {{.ReleaseName}} without spaces)
    - {{ .ReleaseNameTwo }} (for BGM configurations only; also matches {{.ReleaseNameTwo}})
    - {{ .RepoName }} (the full repository name of the target repo, used in infra workflow files; also matches {{.RepoName}})
    - {{ .DeploymentName }} (the deployment name from config/MainManifest.yml deployments[].name, used in Helm values envFrom references; also matches {{.DeploymentName}})
    - {{ .OrgName }} (the GitHub organization name of the target repo, used in infra workflow files; also matches {{.OrgName}})

  CRITICAL: Token Spacing Variants
  The reference template files may use EITHER format for any token:
    - WITH spaces: {{ .ServiceName }}
    - WITHOUT spaces: {{.ServiceName}}
  The agent MUST replace BOTH variants for every token. A regex pattern
  like \{\{\s*\.TokenName\s*\}\} handles both forms in a single pass.
  After replacement, verify NEITHER variant remains in any file.

  Token Replacement Rules (MANDATORY):

  1. Replace ALL instances of {{ .ServiceName }} AND {{.ServiceName}} with the derived service name (lowercase, no hyphens)
  2. Replace ALL instances of {{ .Namespace }} AND {{.Namespace}} with the derived namespace
  3. Replace ALL instances of {{ .ReleaseName }} AND {{.ReleaseName}} with the generated release name
  4. Replace ALL instances of {{ .ReleaseNameTwo }} AND {{.ReleaseNameTwo}} with the secondary release name (BGM only)
  5. Replace ALL instances of {{ .RepoName }} AND {{.RepoName}} with the target repository name
  6. Replace ALL instances of {{ .DeploymentName }} AND {{.DeploymentName}} with the deployment name from config/MainManifest.yml
  7. Replace ALL instances of {{ .OrgName }} AND {{.OrgName}} with the GitHub organization name of the target repo

  DeploymentName Resolution (MANDATORY):
    - The agent MUST read config/MainManifest.yml and locate the deployments[] array
    - {{ .DeploymentName }} MUST be replaced with the `name` field of the deployment
      that corresponds to the current context (e.g., "api", "ui", "worker")
    - In Helm values files, {{ .DeploymentName }} appears in envFrom sections
      (configMapRef and secretRef names) and in job containers
    - For Standard Service with a single deployment: use that deployment's name
    - For services with multiple deployments: match the deployment name to the
      context where the token appears (e.g., migration jobs use the backend/api deployment)
    - If config/MainManifest.yml does not exist yet (being generated in Step 10a):
      use the deployment name determined during repository scanning
    - The deployment name is typically: "api" (backend), "ui" (frontend), or the
      specific deployment name from the manifest
    - Example: If MainManifest.yml has `deployments: [{name: api, ...}]`,
      then {{ .DeploymentName }} in the values file becomes "api"
  
  Supported ServiceName transforms (EXACT patterns):
  
  a) Uppercase + underscore:
     {{ .ServiceName | replace "-" "_" | upper }}
     → SERVICE_NAME (all uppercase)
     
     Example: If ServiceName = test-repo
     {{ .ServiceName | replace "-" "_" | upper }}_IMAGE_TAG
     → TEST_REPO_IMAGE_TAG
  
  b) Lowercase + underscore:
     {{ .ServiceName | replace "-" "_" | lower }}
     → service_name (all lowercase)
     
     Example: If ServiceName = test-repo
     {{ .ServiceName | replace "-" "_" | lower }}
     → test_repo
  
  Helm Values File Pattern (CRITICAL):
  Pattern in helm/values files: {{{ .ServiceName | replace "-" "_" | upper }}_IMAGE_TAG}
  Note the TRIPLE opening braces: {{{
  
  Step 1: Apply transform → {{{TEST_REPO_IMAGE_TAG}
  Step 2: Normalize to: #{TEST_REPO_IMAGE_TAG}#
  
  The triple brace {{{ MUST be replaced with #{
  The closing brace } MUST be replaced with }#
  
  Example:
  {{{ .ServiceName | replace "-" "_" | upper }}_IMAGE_TAG}
  → #{TEST_REPO_IMAGE_TAG}#
  
  Execution Requirements:
  - Token replacement MUST happen IMMEDIATELY after files are copied
  - Process ALL copied files in a single pass
  - Verify no remaining {{ .ServiceName }}, {{.ServiceName}}, {{ .Namespace }}, {{.Namespace}}, {{ .ReleaseName }}, {{.ReleaseName}}, {{ .RepoName }}, {{.RepoName}}, {{ .DeploymentName }}, {{.DeploymentName}}, {{ .OrgName }}, {{.OrgName}} tokens
  - Verify all transforms are applied correctly
  - If ANY unreplaced token remains → STOP with clear error showing file and line
  
  Files MUST include (token replacement is MANDATORY for):
  - ALL workflow files (.github/workflows/*.yml)
  - ALL Helm values files (helm/**/values-*.yml)
  - ALL azureConfig files (manage-appConfig-secrets/azureConfig/*.yml)
  - ALL bgmConfig files (manage-appConfig-secrets/bgmConfig/*.yml)
  - manage-appConfig-secrets/manage-azureConfig.ps1 (PowerShell script)
  
  Critical: manage-azureConfig.ps1 contains patterns like:
    bgm-configuration-{{ .ServiceName }}-eastus-$Environment
  
  The agent MUST replace {{ .ServiceName }} in this file with the derived service name.
  PowerShell variables like $Environment MUST NOT be modified.
  
  Rules:
  - Pipe transforms are allowed ONLY on ServiceName
  - Only replace "-", "_", upper, lower are supported
  - Transform order MUST be respected: replace → upper/lower
  - upper = ALL UPPERCASE characters
  - lower = all lowercase characters
  - Any unsupported transform → STOP with error
  
  DO NOT:
    - Modify YAML structure
    - Modify jobs or steps
    - Modify comments
    - Modify ACR placeholders
    - Modify PowerShell variables (e.g., $Environment, $Name)

  ----------------------------------------------------------------------
  ✔ MICRO-FRONTEND DEPLOYMENT STRUCTURE (HARD RULE)
  ----------------------------------------------------------------------
  
  For Micro-Frontend services:
  
  - Each micro-frontend application MUST be deployed as:
      - A separate Kubernetes Deployment
      - Containing EXACTLY ONE container
  
  Rules:
  1. The agent MUST generate:
       - One `deployments[]` entry per micro-frontend app
  
  2. Each `deployments[]` entry MUST contain:
       - Exactly ONE container
       - The container name MUST match the deployment name
  
  3. The agent MUST NOT:
       - Create a single Deployment with multiple containers
       - Group multiple micro-frontends into the same pod
  
  4. Shared configuration rules:
       - AppConfig and KeyVault references remain shared
       - `envFrom` MUST be identical across all deployments
  
  5. Conditional Build Logic (CRITICAL):
     
     For micro-frontend Docker builds:
     - Each micro-frontend app MUST have its own build job
     - Each build job MUST have a condition that checks for changes
     - Build job SHOULD ONLY execute if:
         a) Changes detected in the app's source folder, OR
         b) User manually triggers with "force build all" input
     - By DEFAULT, do NOT build all apps on every commit
     
     Implementation:
     - Use GitHub Actions path filters or similar mechanism
     - Each build job must check: `if: contains(github.event.changes, 'apps/<app-name>/**') || inputs.force_build_all`
     - Structure docker workflow to detect changed paths
     - Map changed paths to specific build jobs
     - Skip builds for unchanged apps
     
     Example structure:
     ```yaml
     jobs:
       detect-changes:
         outputs:
           app1_changed: ${{ steps.filter.outputs.app1 }}
           app2_changed: ${{ steps.filter.outputs.app2 }}
       
       build-app1:
         needs: detect-changes
         if: needs.detect-changes.outputs.app1_changed == 'true' || inputs.force_build_all
       
       build-app2:
         needs: detect-changes
         if: needs.detect-changes.outputs.app2_changed == 'true' || inputs.force_build_all
     ```
     
     This ensures efficient builds and prevents unnecessary Docker image creation.

  ----------------------------------------------------------------------
  ✔ MICRO-FRONTEND MULTI-IMAGE WORKFLOW HANDLING (CRITICAL)
  ----------------------------------------------------------------------
  
  For Micro-Frontend services with MULTIPLE containers:
  
  The agent MUST ensure ALL discovered micro-frontend images are properly
  handled in ALL workflow files, not just the build workflow.
  
  MANDATORY FILE UPDATES:
  
  1. docker.acr-images.promote-{{ .ServiceName }}.yml
     - MUST include promote steps for EACH discovered micro-frontend image
     - Each image MUST have its own promote job or step
     - Image names MUST match those discovered from Dockerfile
     - Pattern: For each container (admin, employee, etc.):
         * Promote from devops registry to target registry
         * Use correct image tag for each container
  
  2. docker.step.build-push-{{ .ServiceName }}.yml
     - MUST include build steps for ALL discovered images
     - Each container MUST have its own build configuration
     - Conditional logic MUST be applied per container
  
  3. helm.step.{{ .ServiceName }}.deploy.yml
     - MUST include image tag handling for ALL discovered images
     - MUST set image tags for ALL containers in the values file
     -  Get Docker Image show-tags for micro-frontend-docker and Replace tokens in values files steps to handle the Image tag for all.

  4. helm/{{ .ServiceName }}/values-{{ .ServiceName }}.yml
     - MUST include separate deployments[] entries for EACH container
     - MUST include separate service[] entries for EACH container
     - MUST include separate ingress paths for EACH container
     - Each deployment MUST have:
         * Unique name matching container name
         * Single container matching the deployment name
         * Correct image repository and tag placeholder
     - Each service MUST route to its corresponding deployment
     - Each ingress path MUST route to its corresponding service
  
  5. BGM Workflow Files (Blue-Green deployment workflows)
     The following BGM workflow files MUST handle ALL discovered images:
     
     a) bgm.helm.step.{{ .ServiceName }}.deploy.yml
        - MUST include image tag handling for ALL discovered images
        - MUST set image tags for ALL containers (similar to helm.step)
        - Get Docker Image show-tags steps for EACH container
        - Replace tokens in values files for EACH container's image tag
     
     b) bgm.helm.lwr.{{ .ServiceName }}.deploy.yml (lower environment)
        - MUST pass image information for ALL containers
        - MUST handle deployment of ALL discovered images
     
     c) bgm.helm.upr.{{ .ServiceName }}.deploy.yml (upper environment)
        - MUST pass image information for ALL containers
        - MUST handle deployment of ALL discovered images
     
     Pattern: If service has 2 containers (admin, employee):
     - Each BGM workflow MUST handle both images
     - Image tag retrieval steps for BOTH containers
     - Helm value replacement for BOTH containers
     - Deployment orchestration for BOTH containers
  
  CRITICAL EXECUTION RULES:
  
  1. After Dockerfile analysis discovers multiple containers:
     - The agent MUST immediately update ALL workflow files above
     - This includes: docker, helm, AND BGM workflow files
     - The agent MUST NOT assume single-image structure
  
  2. If ANY workflow file is missing multi-image handling:
     → Make changes to those files
  
  This rule is NON-NEGOTIABLE.
  Any violation MUST be corrected before creating a PR.
  
  2. If ANY file is missing multi-image handling:
     → Make changes to those files
  
  This rule is NON-NEGOTIABLE.
  Any violation MUST be corrected before creating a PR.

  ----------------------------------------------------------------------
  ✔ DOCKERFILE-AWARE AUGMENTATION
  ----------------------------------------------------------------------

  - Read Dockerfile in target repo
  - If Micro-Frontend detected:
      - Add additional container repository entries
  - Else:
      - Extract exposed port
      - Update ONLY port values in Helm values file
  Additive only. No deletions. No reformatting.
  
  Once Micro-Frontend vs Standard Service is determined:
  - ALL generated files MUST reflect that classification.
  - No later step may override or ignore this result.
  If any file violates this invariant:
    → The agent MUST correct it during the single reconciliation phase
    
The result of Dockerfile-aware augmentation is AUTHORITATIVE.
    
  ----------------------------------------------------------------------
  ✔ DOCKERFILE DISCOVERY & BUILD CONTEXT RESOLUTION (MANDATORY)
  ----------------------------------------------------------------------

  The agent MUST NOT assume the Dockerfile name, location, or count.
  The agent MUST also NOT assume a single Dockerfile means a single service.

  Dockerfile discovery rules (STRICT):

  1. The agent MUST scan the ENTIRE TARGET repository for Dockerfiles using:
     - Filenames matching:
         - Dockerfile
         - Dockerfile.*
     - Case-sensitive
     - Search ALL directories, not just root

  2. Classification based on discovery results:

     a) SINGLE Dockerfile found (any location):
        - Derive:
            - dockerfile path (relative to repo root)
            - build context (parent directory of Dockerfile)
        - The agent MUST then inspect the Dockerfile content to determine
          whether it serves ONE service or MULTIPLE services:

          i) Single-service Dockerfile:
             - Contains a single final stage with one EXPOSE port
             - Produces one container image
             - This is the STANDARD SINGLE-BUILD scenario
             - One build configuration in docker workflows

          ii) Multi-service Dockerfile (single file, multiple services):
              - A SINGLE Dockerfile that builds MULTIPLE services
              - Common patterns:
                * Multi-stage builds where different stages produce different
                  service images (e.g., frontend stage and backend stage)
                * Build arguments (ARG) used to select which service/app to build
                  (e.g., ARG APP_NAME selects between apps)
                * Multiple EXPOSE ports for different services within stages
              - Detection rules:
                * Check for multiple named build stages that each produce
                  a runnable image (each has its own CMD/ENTRYPOINT)
                * Check for ARG instructions that control which app/service
                  is built (e.g., ARG APP_NAME, ARG BUILD_TARGET)
                * Check for multiple EXPOSE instructions in different stages
                * Inspect repository structure for multiple app directories
                  that the Dockerfile references
              - When detected, this follows the SAME pattern as Micro-Frontend
                multi-image handling (see MICRO-FRONTEND MULTI-IMAGE WORKFLOW
                HANDLING section). The agent MUST NOT create separate workflow
                files. Instead:
                * Use the SAME set of workflow files (no additional pipeline files)
                * Include multiple build jobs/steps WITHIN each workflow file
                  for each service image
                * Each build job MUST pass the appropriate build arguments or
                  target the correct stage to produce the specific service image
                * Image names MUST distinguish between services
                  (e.g., {{ .ServiceName }}-frontend, {{ .ServiceName }}-backend)
                * Helm values file MUST have separate deployments[], services[],
                  and ingress entries for each service
                * Docker promote workflow MUST promote ALL images
                * BGM workflows MUST handle ALL images
                * helm.step workflow MUST set image tags for ALL services

     b) MULTIPLE Dockerfiles found in DIFFERENT directories:
        - This is the MULTI-DOCKERFILE scenario
        - Each Dockerfile typically maps to a different deployment component
          (e.g., frontend/Dockerfile → frontend build, backend/Dockerfile → backend build)
        - The agent MUST:
            * Identify which component each Dockerfile serves based on:
              - Parent directory name (e.g., frontend/, backend/, api/, worker/)
              - Dockerfile content (base images, EXPOSE ports, build output)
              - Repository structure (package.json, requirements.txt in same directory)
            * Map each Dockerfile to a deployment component
        - This follows the SAME pattern as Micro-Frontend multi-image handling.
          The agent MUST NOT create separate workflow files per Dockerfile.
          Instead:
            * Use the SAME set of workflow files (no additional pipeline files)
            * Include multiple build jobs/steps WITHIN each workflow file
              for each Dockerfile/component
            * Each build job MUST specify:
              - The correct Dockerfile path
              - The correct build context (parent directory of that Dockerfile)
              - The correct image name/tag for that component
            * Docker promote workflow MUST promote ALL images
            * BGM workflows MUST handle ALL images
            * helm.step workflow MUST set image tags for ALL components
            * Helm values file MUST have separate deployments[], services[],
              and ingress entries for each component
        - If a Dockerfile's purpose cannot be determined:
            → Ask the user which component it serves

     c) MULTIPLE Dockerfiles found in the SAME directory:
        - If one is named exactly "Dockerfile" (no extension), prefer it
        - Others (e.g., Dockerfile.dev, Dockerfile.test) are typically
          non-production and should be excluded
        - If ambiguity remains:
            → Ask the user which file to use for production builds

     d) NO Dockerfile found:
        → STOP with clear error

  3. Dockerfile-to-workflow mapping rules:
     - For SINGLE Dockerfile, single service: One build configuration in docker workflows
     - For SINGLE Dockerfile, multiple services: Separate build configurations per service
       WITHIN the same workflow files (following Micro-Frontend multi-image pattern)
       * Each build job targets the same Dockerfile but with different build args or stages
       * Image names MUST distinguish between services
       * Helm values file MUST have matching container/deployment entries for each service
     - For MULTI-DOCKERFILE: Separate build configurations per component
       WITHIN the same workflow files (following Micro-Frontend multi-image pattern)
       * Each build job targets its specific Dockerfile and build context
       * Image names MUST distinguish between components
         (e.g., {{ .ServiceName }}-frontend, {{ .ServiceName }}-backend)
       * Helm values file MUST have matching container/deployment entries
         for each component

  Dockerfile usage rules:
  - The resolved Dockerfile path(s) MUST be injected into:
      - docker-build-push-{{ .ServiceName }}.yml
      - docker.step.build-push-{{ .ServiceName }}.yml
  - The resolved build context(s) MUST be updated accordingly
  - YAML structure MUST NOT be changed beyond adding build jobs for
    additional services/Dockerfiles
  - For multi-service or multi-Dockerfile repos, the agent MUST ensure ALL
    docker, helm, and BGM workflow files handle ALL discovered components
  - The agent MUST NOT create separate workflow files per service or Dockerfile.
    All build jobs MUST be added WITHIN the existing workflow file set
    (following the Micro-Frontend multi-image handling pattern).
  This resolution MUST occur BEFORE:
    - Dockerfile-aware augmentation
    - Post-processing reconciliation

    ----------------------------------------------------------------------
    ✔ AZURE PRECHECK (FAST PATH FIRST)
    ----------------------------------------------------------------------

    Azure CLI and login must be verified BEFORE environment scripts execute.

    FAST PATH:

    1. Check if Azure CLI exists:
    - Run: az --version
    - If success → Continue
    - If failure → Install Azure CLI

    2. Check login status:
    - Run: az account show
    - If success → User already logged in → Continue
    - If failure → Execute az login

    Rules:
    - DO NOT reinstall Azure CLI if already installed.
    - DO NOT re-run az login if already authenticated.
    - DO NOT perform redundant verification.
    - DO NOT attempt multiple login retries.

    If login fails:
    → STOP with clear error.

  ----------------------------------------------------------------------
  ✔ EXECUTION CONSTRAINTS - ENVIRONMENT PHASE
  ----------------------------------------------------------------------
  
  FORBIDDEN ACTIONS during environment setup:
  
  1. DO NOT execute:
     - Start-Sleep (any duration)
     - Repeated environment listing
     - Polling loops
     - Status checks between script executions
  
  2. REQUIRED BEHAVIOR:
     - Execute set-env-secrets.ps1
     - IMMEDIATELY execute set-env-vars.ps1 (no delay)
     - THEN verify (single pass)
     - THEN assign reviewers
  
  3. The agent MUST NOT:
     - Add wait times between script executions
     - List environments multiple times unnecessarily
     - Verify environment existence before each operation
  
  If agent adds sleep/wait commands between scripts:
  → Treat as execution error
  → Remove all unnecessary delays

  ----------------------------------------------------------------------
  ✔ ENVIRONMENT SETUP (SEQUENTIAL EXECUTION)
  ----------------------------------------------------------------------
  Reference scripts:
    scripts/github/environments/
      - set-env-secrets.ps1
      - set-env-vars.ps1
  
  CRITICAL EXECUTION RULES:
  
  This phase MUST be executed sequentially without interruption.
  
  The agent MUST:
  1. Complete ALL other work BEFORE starting this phase
  2. Execute scripts sequentially, NOT in parallel
  3. Verify completion at each step
  4. Retry once if operations fail
  
  Preflight rules:
  1. Azure CLI (az)
     - If az is NOT installed:
         - Attempt to install Azure CLI automatically
         - Use OS-appropriate install method
     - If installation fails:
         → STOP with clear error
  2. Azure Login
     - If user is NOT logged in:
         - Execute `az login`
         - Allow interactive authentication
     - If login fails or is cancelled:
         → STOP with clear error
  
  Execution rules:
    - Do NOT echo secrets
    - Do NOT store credentials
    - Do NOT proceed without confirmed login
    - Execute operations ONE AT A TIME
    - Log each operation clearly
  
  Environment setup scripts MUST be treated as a single atomic phase.
  BOTH scripts MUST complete successfully before proceeding.
  If either script fails:
      → STOP immediately
      → DO NOT attempt reviewer assignment
  
  Execution sequence (STRICT):
  
  1. Execute set-env-secrets.ps1 -env all
     - Wait for complete execution
     - Do NOT interrupt
     - Log all output
     - This script creates SECRETS ONLY
  
  2. Execute set-env-vars.ps1 -env all
     - Wait for complete execution
     - Do NOT interrupt
     - Log all output
     - This script creates VARIABLES ONLY
  
  CRITICAL: Both scripts MUST be executed.
  - set-env-secrets.ps1 creates environment SECRETS
  - set-env-vars.ps1 creates environment VARIABLES
  - They are SEPARATE operations
  - Both MUST complete successfully
  - Do NOT skip set-env-vars.ps1
  - Do NOT assume variables are created by secrets script
  
  3. Post-environment verification (MANDATORY):
     - List all created environments
     - For EACH environment:
         * Verify all expected secrets exist
         * Verify all expected variables exist
         * Log verification status
     - If ANY secret or variable is missing:
         → Retry creation ONCE
         → If still missing after retry → STOP with clear error
  
  4. Proceed to reviewer assignment (separate phase)
  
  Execution constraints:
    - ONLY the above two scripts may be executed
    - Scripts MUST be executed with parameter: -env all
    - No other scripts may be discovered or run
    - If either script is missing → STOP with clear error
    - NO parallel execution

    ----------------------------------------------------------------------
    ✔ ENVIRONMENT SCRIPT EXECUTION MODE (HARD RULE)
    ----------------------------------------------------------------------

    Environment setup scripts MUST be executed directly.
    The agent MUST NOT:
    - Generate intermediate files
    - Write secret values to disk
    - Persist JSON to temporary artifacts
    - Stage secrets in text files
    Secrets MUST be:
    - Retrieved once
    - Passed directly to GitHub APIs
    - Stored without transformation
    If any script requires temporary files:
    → Treat as agent error
    → STOP execution

  ----------------------------------------------------------------------
  ✔ SECRET VALUE INTEGRITY (HARD RULE)
  ----------------------------------------------------------------------

  When setting secrets that contain JSON (e.g. AZURE_CREDENTIAL_AUTOMATION):

  Rules:

  1. The agent MUST treat the value as an opaque string.
  2. The agent MUST NOT:
       - Re-serialize JSON
       - Escape characters
       - Convert objects back to JSON
       - Use PowerShell ConvertTo-Json or ConvertFrom-Json on the value
       - Wrap the JSON in additional quotes
       - Use string interpolation that may alter the JSON

  3. The value MUST be:
       - Retrieved once from Azure Key Vault
       - Passed through unchanged
       - Stored exactly as received
       - Set using raw string passing (NOT through PowerShell variable expansion)

  4. AZURE_CREDENTIAL_AUTOMATION Specific Rules:
     This secret is a JSON credential used for Azure login (az login --service-principal).

     The EXACT JSON format MUST be (single-line, with key-value pairs):
     {"clientSecret":"<value>","subscriptionId":"<value>","tenantId":"<value>","clientId":"<value>","terraformSPObjectId":"<value>"}

     REAL EXAMPLE of valid value:
     {"clientSecret":"OT48Q~0RsC1pMak7o6mXSCItvMUFlfg8U35jIctw","subscriptionId":"11046d88-9e77-479d-961c-4ab32fb23ae7","tenantId":"560ec2b0-df0c-4e8c-9848-a15718863bb6","clientId":"371b9599-37a4-4e09-8c62-f3f7a88dfbdd","terraformSPObjectId":"1167a596-e1c1-4e97-8876-b23ae266fa68"}

     CRITICAL RULES:
     - The value MUST be valid JSON with ALL 5 keys present:
       clientSecret, subscriptionId, tenantId, clientId, terraformSPObjectId
     - The value MUST be a single-line JSON object (no line breaks)
     - The value MUST NOT have extra escaping, double-encoding, or wrapping quotes
     - The value comes from Azure Key Vault already in correct JSON format
     - The agent MUST NOT modify, re-format, or re-serialize the JSON value

     CRITICAL: When setting this secret via gh CLI:
     - Retrieve the value from Azure Key Vault as a RAW string using -o tsv
     - Pass the value directly to gh secret set using stdin piping
     - Do NOT let PowerShell parse or modify the JSON
     - Do NOT use ConvertTo-Json on an already-JSON string
     - Do NOT use double quotes around the value in PowerShell
       (this causes escape issues with inner quotes)
     - Do NOT add spaces, newlines, or formatting to the JSON
     - Do NOT use --body parameter (use stdin piping instead)

     Correct pattern (PowerShell):
     ```powershell
     # Get raw secret value from Key Vault - use -o tsv to get plain text
     $secretValue = az keyvault secret show --vault-name "..." --name "..." --query "value" -o tsv

     # Set it directly - pipe raw value, do NOT re-serialize
     $secretValue | gh secret set AZURE_CREDENTIAL_AUTOMATION --env {env} --repo {owner}/{repo}
     ```

     WRONG patterns (will corrupt the JSON):
     ```powershell
     # WRONG: ConvertTo-Json on already-JSON string adds extra escaping
     $obj | ConvertTo-Json | gh secret set ...

     # WRONG: Double-quoting causes PowerShell to escape inner quotes
     gh secret set AZURE_CREDENTIAL_AUTOMATION --body "$secretValue" ...

     # WRONG: Re-serializing loses formatting or adds unwanted whitespace
     $parsed = $secretValue | ConvertFrom-Json
     $parsed | ConvertTo-Json -Compress | gh secret set ...

     # WRONG: Using -o json instead of -o tsv wraps value in extra quotes
     $secretValue = az keyvault secret show ... --query "value" -o json

     # WRONG: Using string interpolation in PowerShell
     gh secret set AZURE_CREDENTIAL_AUTOMATION --body "$($secretValue)" ...
     ```

  5. After setting the secret:
       - The agent MUST validate that the stored value
         is valid JSON by parsing it once locally.
       - Specifically for AZURE_CREDENTIAL_AUTOMATION:
         * Parse the retrieved value with ConvertFrom-Json ONCE for validation
         * Verify it contains: clientSecret, subscriptionId, tenantId, clientId
         * If validation fails → Correct it as expected
         * The validated value must NOT be re-serialized - use the ORIGINAL string

  If validation fails:
    → STOP with clear error

  ----------------------------------------------------------------------
  ✔ CROSS-ORGANIZATION REPOSITORY ACCESS (CONDITIONAL)
  ----------------------------------------------------------------------

  When the target repository is OUTSIDE the "Shared-Technology-Group" GitHub
  organization, workflow files that reference Shared-Technology-Group repositories
  will FAIL because they cannot access private repos across organizations.

  Detection:
  - Check the target repository's owner/organization
  - If owner == "Shared-Technology-Group" → SKIP this entire section
  - If owner != "Shared-Technology-Group" → Apply the rules below

  Rules:

  1. Scan ALL workflow files (.github/workflows/*.yml) for references to
     Shared-Technology-Group repositories. Look for:
     - uses: Shared-Technology-Group/{repo}/.github/...
     - repository: Shared-Technology-Group/{repo}
     - Any path referencing Shared-Technology-Group

  2. For EACH workflow file that references a Shared-Technology-Group repo:
     - Add a checkout step that uses the org-level REPO_ACCESS_TOKEN secret
     - The checkout step MUST be placed BEFORE any step that uses files
       from the referenced repository
     - Format:
       ```yaml
       - name: Checkout {referenced-repo}
         uses: actions/checkout@v4
         with:
           repository: Shared-Technology-Group/{referenced-repo}
           token: ${{ secrets.REPO_ACCESS_TOKEN }}
           path: .github/.external/{referenced-repo}
           ref: main
       ```
     - Update any subsequent path references in the workflow to use
       the checkout path (.github/.external/{referenced-repo}/...)

  3. REPO_ACCESS_TOKEN:
     - This secret already exists at the ORGANIZATION level
     - Do NOT create or modify this secret
     - Do NOT set it as a repository secret
     - It is automatically available to all repos in the org

  4. Files that typically reference Shared-Technology-Group:
     - Workflow files that use reusable workflows from next-deployment-workflows
     - Workflow files that reference shared action definitions
     - The agent MUST scan each workflow file individually

  5. Do NOT add the checkout step to files that do NOT reference
     Shared-Technology-Group repositories

  This rule is NON-NEGOTIABLE for cross-org repositories.
  Without it, ALL pipelines will fail with authentication errors.

  ----------------------------------------------------------------------
  ✔ GITHUB ENVIRONMENT SCOPE & IDEMPOTENCY
  ----------------------------------------------------------------------

  - All GitHub Environments, secrets, and variables MUST be created
    ONLY in the TARGET repository where the agent is executed.
  - The reference repository MUST NEVER be modified.
  Environment handling rules:
  1. Detect required environments from reference configuration.
  2. For each required environment:
     - Check if the environment already exists in the TARGET repo.
     - If it exists:
         - DO NOT recreate it
         - DO NOT reset existing secrets or variables
     - If it does NOT exist:
         - Create the environment in the TARGET repo
  3. Secrets and variables:
     - MUST be set ONLY in the TARGET repo environments
     - MUST be additive
     - MUST NOT overwrite existing values unless explicitly required
  Any attempt to create or modify environments in the reference repo:
    → STOP immediately with error
  
  ----------------------------------------------------------------------
  ✔ ENVIRONMENT REQUIRED REVIEWERS (POST-SETUP, MANDATORY)
  ----------------------------------------------------------------------

  Required reviewers MUST be added ONLY AFTER:
    - All environments are created
    - All environment secrets are created successfully
    - All environment variables are created successfully
    - ALL verifications pass

  Reviewer assignment MUST NOT block environment creation.

  EXECUTION REQUIREMENTS:

  The agent MUST:
  1. Process ONE environment at a time
  2. Verify reviewer was added after each assignment
  3. Log success/failure for each environment

  Reviewer assignment MUST:
    - Use PowerShell-native piping (`| gh api ... --input -`)
    - NEVER use Bash-style heredocs or redirection
    - NEVER prompt the user for usernames or IDs
    - NEVER request user input during execution

  SERVICE ACCOUNT SETUP (MANDATORY - BEST-EFFORT RESILIENT):

  The agent MUST attempt to add svc-GitHub_aptean as a repository collaborator
  with admin access AND as a required reviewer on ALL environments
  (except devops-build). The agent MUST try aggressively but MUST NOT
  fail the entire execution if it cannot succeed.

  1. Add svc-GitHub_aptean as collaborator:
     - Execute: `gh api "repos/{owner}/{repo}/collaborators/svc-GitHub_aptean" -X PUT -f permission=admin`
     - If this fails → Retry ONCE
     - If retry fails → Try with write permission:
       `gh api "repos/{owner}/{repo}/collaborators/svc-GitHub_aptean" -X PUT -f permission=write`
     - If ALL attempts fail:
       → Log WARNING: "⚠️ Could not add svc-GitHub_aptean as collaborator.
         Please add svc-GitHub_aptean manually with admin access."
       → CONTINUE execution (DO NOT STOP the agent)
       → Mark svc-GitHub_aptean as unavailable for reviewer assignment

  2. Get svc-GitHub_aptean numeric user ID:
     - Execute: `gh api "users/svc-GitHub_aptean" --jq .id`
     - Store as svcUserId
     - If this fails → Retry ONCE
     - If retry fails:
       → Log WARNING: "⚠️ Could not retrieve svc-GitHub_aptean user ID.
         Please add svc-GitHub_aptean as a required reviewer manually."
       → CONTINUE execution (DO NOT STOP the agent)
       → Mark svc-GitHub_aptean as unavailable

  3. svc-GitHub_aptean SHOULD be included as a required reviewer
     on EVERY environment (except devops-build) alongside the
     authenticated user. If the service account is unavailable due to
     failed Steps 1-2, the agent MUST fall back to the authenticated
     user only and log a clear warning for each environment.

  Reviewer resolution order (STRICT - AUTOMATIC):
  1. Resolve the authenticated user's GitHub username:
     - Execute: `gh api user --jq .login`
     - This returns the authenticated user's actual GitHub username
     - Store this value (e.g., hanirudh_aptean, not HAnirudh)
     - This MUST complete without user interaction

  2. Get the user's numeric ID:
     - Execute: `gh api "users/{username}" --jq .id`
     - Where {username} is the value from step 1
     - This returns the numeric user ID required for reviewer assignment
     - Store this value (e.g., 12345678)

  3. If user resolution or ID retrieval fails:
     - Try repository OWNER as fallback:
       * `gh api repos/{owner}/{repo} --jq .owner.login`
       * Then get owner's ID: `gh api "users/{owner-login}" --jq .id`

  4. If all methods fail for the authenticated user:
     - STOP with clear error - at least the authenticated user is required

  CRITICAL: Reviewer Assignment Format

  Both the authenticated user AND svc-GitHub_aptean MUST be added
  using their NUMERIC USER IDs, NOT username strings.

  The JSON body for reviewer assignment MUST follow this exact structure:
  ```powershell
  # Step 1: Get authenticated username
  $username = gh api user --jq .login

  # Step 2: Get numeric user ID
  $userId = gh api "users/$username" --jq .id

  # Step 3: Get svc-GitHub_aptean numeric user ID
  $svcUserId = gh api "users/svc-GitHub_aptean" --jq .id

  # Step 4: Construct JSON with BOTH reviewer IDs
  $json = "{`"reviewers`":[{`"type`":`"User`",`"id`":$userId},{`"type`":`"User`",`"id`":$svcUserId}]}"

  # Step 5: Apply to each environment
  $json | gh api "repos/{owner}/{repo}/environments/{env-name}" -X PUT --input -
  ```
  
  Rules:
  - type MUST be "User" (not "Team")
  - id MUST be the NUMERIC user ID (not username string)
  - NEVER use team names or team IDs
  - NEVER use username as the id value
  - The id field requires the numeric user ID from the GitHub API
  - Use the authenticated user's actual GitHub username (from gh api user)
  - Verify both username and user ID are retrieved before assignment
  - svc-GitHub_aptean SHOULD always be included - fall back to authenticated user only if unavailable

  Reviewer assignment MUST use the following execution pattern:
    - Construct JSON body in PowerShell
    - Pipe JSON via standard input to `gh api --input -`
    - Include `deployment_branch_policy = null`
    - Use ConvertTo-Json with sufficient depth

  Any deviation from this pattern:
    → Treat as execution failure

  Reviewer assignment execution (STRICT):

  For EACH environment (except devops-build):
  1. If svc-GitHub_aptean is available: assign BOTH required reviewers (authenticated user + svc-GitHub_aptean)
     If svc-GitHub_aptean is NOT available: assign authenticated user ONLY and log warning
  2. Verify reviewers were added
  3. If verification fails:
     - Retry ONCE
     - If still fails:
         → Log WARNING: "⚠️ Could not assign reviewers to {env}. Please add manually."
         → CONTINUE to next environment (DO NOT STOP the agent)

  Final verification and reporting (MANDATORY):
  - After all assignments complete
  - Check each environment for reviewers
  - Create a summary of reviewer status per environment
  - If ANY environment is missing a reviewer:
      → Log WARNING with clear list of environments and missing reviewers
      → Include this in the final summary report for user to address manually
      → DO NOT STOP the agent - proceed to commit and PR creation
  - Include this summary in the final agent report

  Rules:
  - Reviewer assignment (including svc-GitHub_aptean) is the GOAL - try aggressively
  - Failure to add svc-GitHub_aptean as collaborator or reviewer MUST NOT fail the agent
  - Instead, log clear warnings and include status in the final report
  - The agent MUST complete all other work first (environment creation, secrets, variables)
  - Manual username input MUST NEVER be requested
  - User prompts during reviewer assignment are FORBIDDEN
  - Existing environments MUST NOT be modified
  - The `devops-build` environment MUST NOT have required reviewers
  - Process environments sequentially
  - Reviewer failures MUST be logged as warnings and included in the final summary
  - The agent MUST continue execution even if reviewer assignment fails

  ----------------------------------------------------------------------
  ✔ MAINMANIFEST.YML GENERATION RULES (BLOCKING)
  ----------------------------------------------------------------------

  Reference files (from cloned reference repo):
    - templates/service/config/MainManifest.example.yml (comprehensive example)
    - templates/service/config/manifest-schema.json (JSON schema for validation)

  RULES FOR config/MainManifest.yml GENERATION:

    1. If config/MainManifest.yml ALREADY exists in the target repository:
       → Do NOT overwrite it
       → Do NOT regenerate it
       → Only add missing required configuration entries if they are not already present
       → If no changes are needed, skip this step entirely

    2. If config/MainManifest.yml does NOT exist in the target repository:
       → The agent MUST read BOTH reference files from the cloned reference repo:
           * templates/service/config/MainManifest.example.yml (for structure and examples)
           * templates/service/config/manifest-schema.json (for schema validation rules)
       → The agent MUST then CREATE a new config/MainManifest.yml from scratch
       → The generated file MUST conform to the manifest-schema.json structure
       → The generated file MUST follow the patterns shown in MainManifest.example.yml

    3. Manifest Structure (as defined by schema and example):
       The generated MainManifest.yml MUST include these sections:

       a) manifestVersion: "1.0"

       b) product:
          - name: Target repository name (human-readable)
          - description: Brief description derived from repo inspection
          - iamDetails:
              code: IamCode in ALL UPPERCASE (NOT ServiceName - they may differ)
                    → e.g., PERSASST (from ServiceName personalassist), CUSTMGMT, INDUSTRYHUB
                    → Alphabetic only, NO hyphens, NO numbers
                    → 2-12 characters, starts with a letter
                    → MUST NOT exceed 12 characters to fit Azure Key Vault 24-char name limit
                    → Used for Key Vault (kv-{region}-{lower(code)}-{env}, e.g., kv-eus-persasst-tst), state files, DB prefixes
                    → Region code is a 3-char Azure region abbreviation (eus, wu2, gwc, etc.)
                    → MUST be globally unique - verify via gh search before finalizing
          - repository:
              url: Target repository URL
              branch: main
          - cloud: azure
          - category: microservice | monolith | library | job (detect from repo)
          - service-domain: Determined from repo context
          - audience: internal | external | partner

       c) infrastructure:
          - databases: Array of database declarations
              * Only "postgres" type is currently supported
              * Each database needs: name, type, sku (per-environment sizing)
              * Secrets auto-stored in infra Key Vault:
                {app}-{name}-connection-string, -db-host, -db-username, -db-password, -db-name
          - storage: [] (FUTURE - not yet implemented)
          - caching: [] (FUTURE - not yet implemented)
          - messaging: [] (FUTURE - not yet implemented)

       d) deployments: Array of Kubernetes workload declarations
          Each deployment MUST include:
          - name: Deployment name (e.g., ui, api, worker)
          - type: frontend | backend | worker | cronjob
            * frontend/backend → K8s Service created, eligible for Ingress
            * worker → No Service, no Ingress (background processing)
            * cronjob → K8s CronJob, no Service
          - port: Container port (1-65535)
          - bgmEnabled: true/false (blue-green deployment support)
          - healthCheck: { path, port, initialDelaySeconds, periodSeconds }
          - languages: Array of languages used (informational)
          - capacity: Array of per-environment resource specs
            * replicas: { min, max } (HPA bounds)
            * resources: { cpu, cpuLimit, memory, memoryLimit }
          - configuration: Array of environment variables
            * name: Variable name (UPPER_SNAKE_CASE)
            * type: applicationConfig | applicationSecret
            * defaultValue: (optional, only for applicationConfig)
            NOTE: ALL secret-type configuration entries MUST use type "applicationSecret". The type "infrastructureSecret" MUST NOT be used. Infrastructure secrets (database credentials, connection strings, etc.) are auto-managed by the platform via Key Vault and do NOT appear in the configuration array.

       e) routing:
          - domains: Per-environment domain mapping
            * Use {name} placeholder for product code (lowercased iamDetails.code)
            * Example: {name}.dev.apteanone.com
          - tls: true
          - routes: Path-based routing rules
            * path, deployment, port (optional), rewrite (optional)

       f) dependencies:
          - internal: [] (internal service dependencies)
          - external: [] (external API dependencies)

    4. Repository Scanning (MANDATORY when creating MainManifest.yml):
       The agent MUST scan the ENTIRE target repository to determine:

       a) Deployments (replaces old "services" concept):
          - Usually a repository can has TWO deployments: ui (frontend) and api (backend)
          - For microfrontend repositories: each microfrontend app is an additional deployment
          - For worker/background services: add as type "worker"
          - The agent MUST inspect the repository structure to determine ALL deployments
          - Detect ports from Dockerfiles, package.json, or application configs
          - Detect health check paths from existing code

       b) Required Configuration (MUST be included for each deployment):
          The following MUST be present in the configuration array:
          - IAM_CLIENT_ID     (type: applicationConfig)
          - IAM_CLIENT_SECRET (type: applicationSecret)
          - IAM_POID          (type: applicationConfig)
          - API_URL           (type: applicationConfig)
          - API_KEY           (type: applicationSecret)
          Additional configuration entries should be added based on
          what the repository code actually uses.

       c) Infrastructure Detection:
          - Scan for database usage:
              * PostgreSQL (only supported type currently)
              * Look for: connection strings, ORM configs, migration files
          - Storage, caching, messaging: Set as empty arrays (FUTURE)
          - If databases cannot be determined with confidence:
              → Ask the user what databases are needed

       d) Routing Detection:
          - Determine appropriate paths based on deployment types
          - Frontend typically gets "/{{ .ServiceName }}" path
          - Backend typically gets "/{{ .ServiceName }}/api" path with rewrite to "/"

    5. iamDetails.code in MainManifest.yml:
       - MUST be ServiceName in ALL UPPERCASE (e.g., PERSASST, CUSTMGMT, INDUSTRYHUB)
       - ServiceName and iamDetails.code are the SAME identity (just different case)
       - Alphabetic only (no numbers, no hyphens)
       - MUST NOT exceed 12 characters (Azure Key Vault 24-char name limit constraint)
       - Key Vault naming: kv-{region}-{lower(iamDetails.code)}-{env} (e.g., kv-eus-persasst-tst)
         Total KV name: 3 (kv-) + 4 (region-) + code (2-12) + 1 (-) + env (3-4) <= 24
       - This is the ONLY file where ServiceName appears in uppercase
       - MUST be globally unique across all Aptean products to prevent:
         * Key Vault name collisions (kv-{region}-{code}-{env} must be unique per Azure subscription)
         * Terraform state file conflicts ({code}-{env}.tfstate)
         * IAM product code conflicts in AppCentral
       - UNIQUENESS CHECK (MANDATORY before finalizing):
         The agent MUST search the GitHub org for existing MainManifest.yml files
         to verify no other product uses the same code:
           gh search code "iamDetails" --owner Shared-Technology-Group --owner Aptean-Labs
         If a collision is found, the agent MUST choose a different code.
       - Avoid generic codes like ASSIST, SERVICE, ADMIN, PORTAL, BACKEND.
         Prefix with product domain for uniqueness (e.g., PERSASST not ASSIST).
