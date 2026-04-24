---
description: Generate a standardized, branded report (HTML or PPTX) using the Polaris design system. Use for any analysis, audit, assessment, or investigation output.
---


## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Goal

Generate a professional, enterprise-branded report using the **Polaris report design system** defined below. Reports can be produced in **HTML** (default) or **PPTX** (PowerPoint) format. This template ensures visual consistency across ALL reports produced by any agent, skill, or workflow. The output is saved to `.polaris/reports/`.

**This is the canonical reference for report generation.** Any agent or skill that produces a report MUST use this design system. Do NOT invent custom colors, fonts, or component styles.

## Report Naming Convention

Save to: `.polaris/reports/{project-slug}-{report-slug}-{YYYY-MM-DD}.{ext}`

- `{project-slug}`: kebab-case project identifier (e.g., `m2m`, `acme-api`, `platform`)
- `{report-slug}`: kebab-case report type (e.g., `codebase-assessment`, `security-audit`, `nfr-readiness`, `bug-root-cause-analysis`, `repo-cleanup`, `migration-readiness`, `dependency-audit`)
- `{YYYY-MM-DD}`: date of generation
- `{ext}`: `html` or `pptx` depending on the chosen format
- Examples:
  - `.polaris/reports/m2m-security-audit-2026-02-19.html`
  - `.polaris/reports/acme-api-codebase-assessment-2026-03-01.pptx`

---

## Format Selection

Determine the output format from the user's input:

| User input contains... | Format |
|------------------------|--------|
| `pptx`, `powerpoint`, `slides`, `deck`, `presentation` | **PPTX** |
| Anything else (or nothing specified) | **HTML** (default) |

- If **HTML**: Follow the **HTML FORMAT** sections below (HTML Skeleton, Design System, Component Catalog, Full CSS Stylesheet, Content Guidelines, HTML Execution Steps, HTML Quality Checklist).
- If **PPTX**: Skip straight to the **PPTX FORMAT** section at the end of this template.

Both formats share the same **Report Naming Convention**, **Report Type** determination (Step 1), **Data Gathering** (Step 2), **Content Guidelines**, and **Operating Principles**.

---

# ===== HTML FORMAT =====

## HTML SKELETON

Every report MUST follow this structure. Sections marked `[REQUIRED]` are mandatory. Sections marked `[CONDITIONAL]` are included when relevant to the report type.

```
 1. DOCTYPE + <head> with full CSS design system            [REQUIRED]
 2. <header class="report-header">                          [REQUIRED]
 3. Framework / methodology note                            [REQUIRED]
 4. Executive summary with score cards                      [REQUIRED]
 5. Dimension score bars                                    [CONDITIONAL - scored reports]
 6. Severity distribution strip                             [CONDITIONAL - findings reports]
 7. Summary narrative box                                   [REQUIRED]
 8. Top priority actions table                              [REQUIRED]
 9. Human vs AI effort comparison                           [CONDITIONAL - first assessments]
10. Detailed findings sections (one <h2> per dimension)     [REQUIRED]
11. Recommendations / roadmap                               [REQUIRED]
12. Methodology & grading scale                             [REQUIRED]
13. Disclaimer callout                                      [REQUIRED]
14. <footer class="report-footer">                          [REQUIRED]
```

---

## DESIGN SYSTEM

CSS variables and typography are defined in the Full CSS Stylesheet section below. Use ONLY those variables -- no ad-hoc hex colors.

---

## COMPONENT CATALOG

Each component below is a reusable building block. Copy the HTML patterns exactly, substituting only the `{{PLACEHOLDER}}` values with actual content.

### 1. Report Header [REQUIRED]

Navy gradient banner with project logo, report title, tag line, metadata, and optional grade circle.

```html
<header class="report-header">
  <div class="container">
    <div class="brand">
      <!-- Project logo SVG goes here (40x38 recommended). -->
      <!-- If no project logo, omit the SVG entirely. -->
      <div>
        <h1>{{REPORT_TITLE}}</h1>
        <span class="tag">{{TAG_LINE}}</span>
      </div>
    </div>
    <div style="display:flex;align-items:center;gap:1.5rem;">
      <div class="meta">
        <span>{{FULL_DATE}}</span>
        <span>{{PROJECT_NAME}}</span>
        <span>{{SCOPE_SUMMARY}}</span>
      </div>
      <!-- Grade circle: include ONLY for scored/graded reports -->
      <div class="grade" style="background:var({{GRADE_COLOR}})">{{GRADE_LETTER}}</div>
    </div>
  </div>
</header>
```

Tag lines: assessment=`ISO 25010 &middot; OWASP TOP 10 &middot; CWE/SANS TOP 25`, security=`OWASP TOP 10 &middot; CWE/SANS TOP 25 &middot; NIST CSF`, NFR=`ISO 25010 &middot; PERFORMANCE &middot; RELIABILITY`, bug=`ROOT CAUSE ANALYSIS &middot; PATTERN DETECTION`, migration=`MODERNIZATION &middot; RISK ASSESSMENT`, deps=`CVE ANALYSIS &middot; LICENSE COMPLIANCE`, general=`POLARIS ANALYSIS`.

Grade colors: A=`--good`, B=`--aptean-teal`, C=`--aptean-orange`, D=`--high`, F=`--critical`.

### 2. Score Cards [REQUIRED for quantitative reports]

```html
<div class="score-grid">
  <div class="score-card">
    <div class="label">{{METRIC_LABEL}}</div>
    <div class="value" style="color:var({{COLOR}})">{{VALUE}}</div>
    <div class="sublabel">{{OPTIONAL_SUBLABEL}}</div>
  </div>
</div>
```

Value colors: neutral=`--aptean-navy`, good=`--good`, warning=`--aptean-orange`, bad=`--high`/`--critical`.

### 3. Score Bars [CONDITIONAL -- scored/graded reports]

```html
<div class="score-bar-row">
  <span class="score-bar-label">{{DIMENSION_NAME}}</span>
  <div class="score-bar-bg">
    <div class="score-bar-fill {{FILL_CLASS}}" style="width:{{PERCENT}}%">
      {{SCORE}} / 10
    </div>
  </div>
  <span class="score-bar-weight">{{WEIGHT}}%</span>
</div>
```

Fill classes: >=6 `s-good` (teal), 4-5 `s-ok` (orange), <=3 `s-low` (red).

### 4. Severity Badges [REQUIRED for all findings]

```html
<span class="badge badge-critical">CRITICAL</span>
<span class="badge badge-high">HIGH</span>
<span class="badge badge-medium">MEDIUM</span>
<span class="badge badge-low">LOW</span>
<span class="badge badge-good">GOOD</span>       <!-- or STRENGTH / PASS -->
<span class="badge badge-na">N/A</span>
<span class="badge badge-pass">PASS</span>
<span class="badge badge-partial">PARTIAL</span>
<span class="badge badge-missing">MISSING</span>
<span class="badge badge-fail">FAIL</span>
```

### 5. Dimension Score Tags [CONDITIONAL -- inline with h2]

`<h2>1. Security <span class="dim-score warn">4/10</span></h2>` -- Classes: >=8 `great`, 6-7 `good`, 4-5 `ok`, 2-3 `warn`, 0-1 `bad`.

### 6. Summary Boxes [REQUIRED -- at least one per section]

```html
<!-- Standard (teal accent) -->
<div class="summary-box"><p>{{NARRATIVE}}</p></div>

<!-- Danger (red accent) -- for critical findings -->
<div class="summary-box danger"><p>{{CRITICAL_NARRATIVE}}</p></div>

<!-- Warning (orange accent) -->
<div class="summary-box warn"><p>{{WARNING_NARRATIVE}}</p></div>
```

### 7. Framework / Methodology Note [REQUIRED]

```html
<div class="framework-note">
  <h3>{{METHODOLOGY_TITLE}}</h3>
  <p>{{DESCRIPTION}}</p>
  <ul>
    <li><strong>Scan strategy:</strong> {{STRATEGY_DETAIL}}</li>
    <li><strong>Standards applied:</strong> {{STANDARDS_LIST}}</li>
    <li><strong>Scope:</strong> {{SCOPE_DETAIL}}</li>
  </ul>
</div>
```

Critical variant: add `style="background:var(--critical-bg);border-color:#ffcdd2;"` and `style="color:var(--critical);"` on h3.

### 8. Tables [REQUIRED for findings]

```html
<table>
  <thead>
    <tr>
      <th style="width:75px">Severity</th>
      <th>Finding</th>
      <th style="width:200px">Location</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><span class="badge badge-critical">CRITICAL</span></td>
      <td><strong>{{FINDING_TITLE}}.</strong> {{FINDING_DESCRIPTION}}</td>
      <td><code>{{FILE_PATH}}</code></td>
    </tr>
  </tbody>
</table>
```

Tables: always `<thead>`, `class="num"` on numeric cells, `<code>` for paths, explicit `width` on narrow columns.

### 9. Callout Boxes [CONDITIONAL]

```html
<!-- Warning (orange) -->
<div class="callout"><strong>{{TITLE}}:</strong> {{MESSAGE}}</div>

<!-- Danger (red) -->
<div class="callout danger"><strong>{{TITLE}}:</strong> {{MESSAGE}}</div>

<!-- Info (teal) -->
<div class="callout info"><strong>{{TITLE}}:</strong> {{MESSAGE}}</div>
```

### 10. Evidence Blocks [CONDITIONAL -- for supporting data]

```html
<div class="evidence">
  <strong>Evidence:</strong> {{EVIDENCE_DESCRIPTION}}
</div>
```

### 11. Code Blocks [CONDITIONAL -- for code samples]

```html
<div class="code-block">
<span class="comment">// Comment text</span>
<span class="keyword">var</span> example = <span class="string">"value"</span>;
<span class="highlight">functionCall</span>();
<span class="error">// ERROR: problem description</span>
</div>
```

### 12. Severity Distribution Strip [CONDITIONAL -- findings summary]

```html
<div class="severity-strip">
  <div class="severity-item">
    <div class="severity-dot" style="background:var(--critical)"></div> {{N}} Critical
  </div>
  <div class="severity-item">
    <div class="severity-dot" style="background:var(--high)"></div> {{N}} High
  </div>
  <div class="severity-item">
    <div class="severity-dot" style="background:var(--medium)"></div> {{N}} Medium
  </div>
  <div class="severity-item">
    <div class="severity-dot" style="background:var(--good)"></div> {{N}} Low
  </div>
</div>
```

### 13. Human vs AI Effort Comparison [CONDITIONAL -- first assessments]

```html
<div class="time-compare">
  <div class="time-card human">
    <div class="time-label">Human Team (Realistic)</div>
    <div class="time-value" style="color:var(--aptean-orange)">{{HUMAN_TIME}}</div>
    <div class="time-sub">{{HUMAN_DETAIL}}</div>
  </div>
  <div class="time-card ai">
    <div class="time-label">AI-Powered (Polaris Framework)</div>
    <div class="time-value" style="color:var(--aptean-teal)">{{AI_TIME}}</div>
    <div class="time-sub">{{AI_DETAIL}}</div>
  </div>
</div>
```

### 14. Disclaimer [REQUIRED -- placed before footer]

```html
<div class="callout" style="margin-top:1.5rem;">
  <strong>Disclaimer:</strong> This {{REPORT_TYPE_LOWER}} is based on {{METHODOLOGY_SUMMARY}}.
  Findings should be validated against production telemetry and prioritized based on business impact.
  Analysis performed on {{DATE}} against branch <code>{{BRANCH}}</code>.
</div>
```

### 15. Report Footer [REQUIRED]

```html
<footer class="report-footer">
  <strong>Polaris Software Intelligence Framework</strong>
  &middot; {{REPORT_TYPE}} &middot; {{PROJECT_NAME}}<br>
  Generated {{DATE}} &middot; {{SCOPE_STATS}}<br>
  &copy; {{YEAR}} {{ORG_NAME}}. All rights reserved.
</footer>
```

---

## FULL CSS STYLESHEET

Every report MUST include this exact CSS block inside `<style>` in the `<head>`. Do NOT modify these styles. If a report type needs a unique visualization (spider chart, treemap, Sankey diagram), ADD new classes at the end -- never change existing ones. All additions MUST reference the existing CSS variables.

```html
<style>
  @import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap');

  :root {
    --aptean-navy: #001f54;
    --aptean-navy-light: #0a2e6b;
    --aptean-teal: #1e6978;
    --aptean-teal-light: #26c6da;
    --aptean-teal-bg: rgba(30,105,120,0.06);
    --aptean-blue: #2196f3;
    --aptean-orange: #d98543;
    --bg: #f8f9fb;
    --surface: #ffffff;
    --surface-alt: #f1f4f8;
    --border: #e2e7ef;
    --border-light: #edf0f5;
    --text: #262626;
    --text-secondary: #5a6776;
    --text-muted: #8694a1;
    --critical: #d32f2f; --critical-bg: #fef2f2;
    --high: #e65100; --high-bg: #fff7ed;
    --medium: #f57f17; --medium-bg: #fffde7;
    --low: #5c6bc0; --low-bg: #eef0fb;
    --good: #2e7d32; --good-bg: #f0fdf4;
  }

  * { margin:0; padding:0; box-sizing:border-box; }

  body {
    font-family:'Inter',system-ui,sans-serif;
    background:var(--bg);
    color:var(--text);
    line-height:1.65;
    -webkit-font-smoothing:antialiased;
  }

  /* -- Layout -- */
  .container { max-width:1140px; margin:0 auto; padding:0 2rem; }
  .content { padding:2rem 0 3rem; }

  /* -- Report Header -- */
  .report-header {
    background:linear-gradient(135deg,var(--aptean-navy) 0%,var(--aptean-navy-light) 100%);
    color:white; padding:2.5rem 0;
  }
  .report-header .container {
    display:flex; align-items:center; justify-content:space-between;
    flex-wrap:wrap; gap:1.5rem;
  }
  .report-header .brand { display:flex; align-items:center; gap:1rem; }
  .report-header h1 { font-size:1.5rem; font-weight:700; letter-spacing:-0.02em; }
  .report-header .tag {
    display:inline-block;
    background:rgba(38,198,218,0.2); border:1px solid rgba(38,198,218,0.4);
    color:var(--aptean-teal-light); padding:2px 12px; border-radius:20px;
    font-size:0.75rem; font-weight:600; margin-top:0.3rem; letter-spacing:0.04em;
  }
  .report-header .meta {
    font-size:0.85rem; opacity:0.7; display:flex; gap:1.5rem; flex-wrap:wrap;
  }
  .report-header .grade {
    display:flex; align-items:center; justify-content:center;
    width:64px; height:64px; border-radius:50%;
    font-size:1.75rem; font-weight:800; background:var(--aptean-orange);
    color:white; flex-shrink:0;
  }

  /* -- Headings -- */
  h2 {
    font-size:1.25rem; font-weight:700; color:var(--aptean-navy);
    margin:2.5rem 0 1rem; padding-bottom:0.5rem;
    border-bottom:2px solid var(--aptean-teal);
    display:flex; align-items:center; justify-content:space-between;
    flex-wrap:wrap; gap:0.5rem;
  }
  h3 { font-size:1rem; font-weight:600; color:var(--aptean-navy); margin:1.5rem 0 0.75rem; }
  h4 { font-size:0.92rem; font-weight:600; color:var(--aptean-navy); margin:1rem 0 0.5rem; }

  /* -- Framework Note -- */
  .framework-note {
    background:var(--aptean-teal-bg); border:1px solid rgba(30,105,120,0.15);
    border-radius:12px; padding:1.5rem; margin:1.25rem 0;
  }
  .framework-note h3 { margin-top:0; font-size:0.95rem; }
  .framework-note p { color:var(--text-secondary); font-size:0.88rem; margin-top:0.35rem; }
  .framework-note ul { margin:0.5rem 0 0 1.25rem; font-size:0.85rem; color:var(--text-secondary); }
  .framework-note li { margin:0.25rem 0; }

  /* -- Score Grid -- */
  .score-grid {
    display:grid; grid-template-columns:repeat(auto-fit,minmax(135px,1fr));
    gap:0.75rem; margin:1.25rem 0;
  }
  .score-card {
    background:var(--surface); border:1px solid var(--border);
    border-radius:10px; padding:1rem 1.25rem; text-align:center;
    transition:box-shadow 0.15s;
  }
  .score-card:hover { box-shadow:0 2px 12px rgba(0,31,84,0.07); }
  .score-card .label {
    font-size:0.7rem; font-weight:600; text-transform:uppercase;
    letter-spacing:0.06em; color:var(--text-muted); margin-bottom:0.25rem;
  }
  .score-card .value { font-size:1.6rem; font-weight:800; line-height:1.2; }
  .score-card .sublabel { font-size:0.66rem; color:var(--text-muted); margin-top:0.15rem; }

  /* -- Score Bars -- */
  .score-bar-row { display:flex; align-items:center; gap:0.75rem; margin:0.4rem 0; }
  .score-bar-label { min-width:130px; font-size:0.85rem; font-weight:600; color:var(--text); }
  .score-bar-bg {
    flex:1; height:24px; background:#e8edf3; border-radius:4px;
    position:relative; overflow:hidden;
  }
  .score-bar-fill {
    height:100%; border-radius:4px; display:flex; align-items:center;
    padding:0 10px; font-size:0.72rem; font-weight:700; color:white;
    transition:width 0.4s;
  }
  .score-bar-fill.s-good { background:var(--aptean-teal); }
  .score-bar-fill.s-ok   { background:var(--aptean-orange); }
  .score-bar-fill.s-low  { background:var(--critical); }
  .score-bar-weight { min-width:45px; font-size:0.72rem; color:var(--text-muted); text-align:right; }

  /* -- Badges -- */
  .badge {
    display:inline-block; padding:2px 10px; border-radius:10px;
    font-size:0.7rem; font-weight:700; letter-spacing:0.04em; text-transform:uppercase;
  }
  .badge-critical { background:var(--critical-bg); color:var(--critical); border:1px solid #ffcdd2; }
  .badge-high     { background:var(--high-bg);     color:var(--high);     border:1px solid #ffe0b2; }
  .badge-medium   { background:var(--medium-bg);   color:var(--medium);   border:1px solid #fff9c4; }
  .badge-low      { background:var(--low-bg);      color:var(--low);      border:1px solid #c5cae9; }
  .badge-good     { background:var(--good-bg);     color:var(--good);     border:1px solid #c8e6c9; }
  .badge-na       { background:var(--surface-alt);  color:var(--text-muted); border:1px solid var(--border); }
  .badge-pass     { background:var(--good-bg);     color:var(--good);     border:1px solid #c8e6c9; }
  .badge-partial  { background:var(--medium-bg);   color:var(--medium);   border:1px solid #fff9c4; }
  .badge-missing  { background:var(--critical-bg); color:var(--critical); border:1px solid #ffcdd2; }
  .badge-fail     { background:#b71c1c;            color:white;           border:1px solid #b71c1c; }

  /* -- Dimension Scores (inline with h2) -- */
  .dim-score { font-size:0.85rem; font-weight:700; padding:3px 12px; border-radius:8px; }
  .dim-score.great { background:var(--good-bg);     color:var(--good); }
  .dim-score.good  { background:#e0f7fa;            color:var(--aptean-teal); }
  .dim-score.ok    { background:var(--medium-bg);   color:var(--medium); }
  .dim-score.warn  { background:var(--high-bg);     color:var(--high); }
  .dim-score.bad   { background:var(--critical-bg); color:var(--critical); }

  /* -- Summary Box -- */
  .summary-box {
    background:var(--surface); border:1px solid var(--border);
    border-left:4px solid var(--aptean-teal);
    border-radius:0 10px 10px 0; padding:1.25rem 1.5rem; margin:0.75rem 0;
  }
  .summary-box p { color:var(--text-secondary); font-size:0.9rem; }
  .summary-box.danger { border-left-color:var(--critical); }
  .summary-box.warn   { border-left-color:var(--high); }

  /* -- Evidence Block -- */
  .evidence {
    background:var(--aptean-teal-bg); border:1px solid rgba(30,105,120,0.15);
    border-radius:8px; padding:1rem 1.25rem; margin:0.75rem 0;
    font-size:0.85rem; color:var(--text-secondary);
  }
  .evidence strong { color:var(--aptean-teal); }

  /* -- Code Block -- */
  .code-block {
    background:#1a1a2e; color:#e0e0e0; padding:1rem 1.25rem;
    border-radius:8px; font-family:'Cascadia Code','Fira Code',monospace;
    font-size:0.78rem; line-height:1.6; margin:0.75rem 0;
    white-space:pre-wrap; overflow-x:auto;
  }
  .code-block .comment   { color:#6a9955; }
  .code-block .keyword   { color:#569cd6; }
  .code-block .string    { color:#ce9178; }
  .code-block .highlight { color:#dcdcaa; }
  .code-block .error     { color:#f44747; }

  /* -- Callout -- */
  .callout {
    background:#fff3e0; border:1px solid #ffe0b2; border-radius:10px;
    padding:1rem 1.25rem; margin:1rem 0; font-size:0.88rem; color:#bf360c;
  }
  .callout strong { color:#e65100; }
  .callout.danger { background:var(--critical-bg); border-color:#ffcdd2; color:#b71c1c; }
  .callout.danger strong { color:var(--critical); }
  .callout.info { background:var(--aptean-teal-bg); border-color:rgba(30,105,120,0.25); color:var(--aptean-teal); }
  .callout.info strong { color:var(--aptean-navy); }

  /* -- Severity Strip -- */
  .severity-strip { display:flex; gap:1.5rem; margin:1rem 0; flex-wrap:wrap; }
  .severity-item { display:flex; align-items:center; gap:0.5rem; font-size:0.85rem; font-weight:600; }
  .severity-dot { width:12px; height:12px; border-radius:50%; }

  /* -- Tables -- */
  table { width:100%; border-collapse:collapse; margin:0.75rem 0; font-size:0.88rem; }
  th {
    background:var(--surface-alt); color:var(--text-muted); font-weight:600;
    font-size:0.73rem; text-transform:uppercase; letter-spacing:0.05em;
    padding:0.65rem 0.75rem; text-align:left; border-bottom:2px solid var(--border);
  }
  td { padding:0.65rem 0.75rem; border-bottom:1px solid var(--border-light); vertical-align:top; }
  tr:hover td { background:var(--surface-alt); }
  td code {
    background:var(--surface-alt); padding:1px 6px; border-radius:4px;
    font-size:0.8rem; color:var(--aptean-teal); border:1px solid var(--border);
    word-break:break-all;
  }
  .text-right { text-align:right; }
  .num {
    font-variant-numeric:tabular-nums;
    font-family:'SF Mono','Cascadia Code',monospace; font-size:0.82rem;
  }

  /* -- Time Comparison Cards -- */
  .time-compare { display:grid; grid-template-columns:1fr 1fr; gap:1.5rem; margin:1rem 0; }
  .time-card {
    background:var(--surface); border:1px solid var(--border);
    border-radius:12px; padding:1.5rem; text-align:center;
  }
  .time-card.human { border-top:4px solid var(--aptean-orange); }
  .time-card.ai    { border-top:4px solid var(--aptean-teal); }
  .time-card .time-label {
    font-size:0.75rem; font-weight:600; text-transform:uppercase;
    letter-spacing:0.06em; color:var(--text-muted); margin-bottom:0.5rem;
  }
  .time-card .time-value { font-size:2rem; font-weight:800; line-height:1.2; }
  .time-card .time-sub { font-size:0.78rem; color:var(--text-secondary); margin-top:0.25rem; }
  .time-detail { font-size:0.82rem; color:var(--text-secondary); text-align:left; margin-top:1rem; }
  .time-detail li { margin:0.3rem 0; }

  /* -- Hotspot Bars -- */
  .hotspot-bar { display:flex; align-items:center; gap:0.5rem; margin:0.35rem 0; font-size:0.82rem; }

  /* -- Footer -- */
  .report-footer {
    background:var(--aptean-navy); color:rgba(255,255,255,0.6);
    text-align:center; padding:1.5rem 2rem; font-size:0.78rem;
  }
  .report-footer strong { color:rgba(255,255,255,0.9); }

  /* -- Print & Responsive -- */
  @media print {
    body { background:white; }
    .report-header { print-color-adjust:exact; -webkit-print-color-adjust:exact; }
  }
  @media (max-width:800px) {
    .container { padding:0 1rem; }
    .score-grid { grid-template-columns:repeat(2,1fr); }
    .time-compare { grid-template-columns:1fr; }
  }
</style>
```

---

## CONTENT GUIDELINES

**Writing**: Evidence-based (cite files+lines in `<code>`), actionable (recommendation+effort), quantified (exact counts, no "many"/"several"), bold key terms (`<strong>`), narrative `summary-box` before each table.

**Severity**: CRITICAL=security/data-loss/production-breaking, HIGH=significant debt/missing critical tests, MEDIUM=quality/moderate debt, LOW=style/cosmetic.

**Grades**: A(8-10)=excellent, B(6-7.9)=good, C(4-5.9)=fair, D(2-3.9)=poor, F(0-1.9)=critical.

**Encoding**: UTF-8 only. Use HTML entities: `&mdash;`, `&ndash;`, `&middot;`, `&copy;`, `&lt;`/`&gt;`. No smart quotes or Office-pasted characters.

---

## EXECUTION STEPS (HTML)

**Step 1: Report Type** -- If not specified, ask: codebase assessment, security audit, NFR readiness, bug root cause, migration readiness, dependency audit, repo cleanup, or custom.

**Step 2: Gather Data** -- Use Grep/Glob/Bash to collect evidence with specific file paths and line numbers.

**Step 3: Compose HTML** -- Assembly: DOCTYPE+head with full CSS -> header -> container.content -> framework-note -> executive summary (score cards, bars, strip) -> summary-box -> priority actions table -> h2 per dimension with findings -> recommendations -> methodology/grading -> disclaimer callout -> close container -> footer.

**Step 4: Save** -- `.polaris/reports/{project-slug}-{report-slug}-{YYYY-MM-DD}.html`

**HTML Checklist**: Only CSS variables (no inline hex), report-header, framework-note, score-grid, summary-box per section, priority table, severity badges on findings, file paths in `<code>`, disclaimer callout, report-footer, HTML entities for special chars, print+responsive styles.

---

# ===== PPTX FORMAT =====

If the user requested PPTX output, follow this section instead of the HTML sections above. Steps 1 (Report Type) and 2 (Gather Data) from the HTML path still apply -- determine the report type and collect all evidence before building slides.

## PPTX Dependency Check

The generated Python script must begin with:

```python
try:
    from pptx import Presentation
    from pptx.util import Inches, Pt, Emu
    from pptx.dml.color import RGBColor
    from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
except ImportError:
    import subprocess, sys
    subprocess.check_call([sys.executable, "-m", "pip", "install", "python-pptx"])
    from pptx import Presentation
    from pptx.util import Inches, Pt, Emu
    from pptx.dml.color import RGBColor
    from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
```

## PPTX Color Palette

Use these `RGBColor` constants throughout the script. Do NOT use ad-hoc hex values.

```python
# ---- Brand ----
CHARCOAL       = RGBColor(0x3A, 0x3A, 0x3A)  # Body text on white
APTEAN_NAVY    = RGBColor(0x05, 0x08, 0x52)  # Headers, strong emphasis
APTEAN_BLUE    = RGBColor(0x24, 0x59, 0xA9)  # Subheadings, links
APTEAN_ORANGE  = RGBColor(0xE6, 0x61, 0x2E)  # Accent, call-to-action
DARK_TEAL      = RGBColor(0x2B, 0x65, 0x83)  # Supporting color
APTEAN_TEAL    = RGBColor(0x54, 0xB3, 0xBE)  # Icons, lighter accents
LIGHT_GRAY     = RGBColor(0xEA, 0xEA, 0xEA)  # Backgrounds, dividers
FOOTER_DARK    = RGBColor(0x1E, 0x23, 0x21)  # Footer bar
BLACK          = RGBColor(0x00, 0x00, 0x00)
WHITE          = RGBColor(0xFF, 0xFF, 0xFF)

# ---- Severity ----
SEV_CRITICAL   = RGBColor(0xD3, 0x2F, 0x2F)
SEV_HIGH       = RGBColor(0xE6, 0x51, 0x00)
SEV_MEDIUM     = RGBColor(0xF5, 0x7F, 0x17)
SEV_LOW        = RGBColor(0x5C, 0x6B, 0xC0)
SEV_GOOD       = RGBColor(0x2E, 0x7D, 0x32)

# ---- Severity backgrounds (light fills for table cells) ----
SEV_CRITICAL_BG = RGBColor(0xFE, 0xF2, 0xF2)
SEV_HIGH_BG     = RGBColor(0xFF, 0xF7, 0xED)
SEV_MEDIUM_BG   = RGBColor(0xFF, 0xFD, 0xE7)
SEV_LOW_BG      = RGBColor(0xEE, 0xF0, 0xFB)
SEV_GOOD_BG     = RGBColor(0xF0, 0xFD, 0xF4)

# ---- Chart sequence ----
CHART_COLORS = [APTEAN_NAVY, APTEAN_BLUE, DARK_TEAL, APTEAN_TEAL, APTEAN_ORANGE]
```

## PPTX Typography

Titles: "Suisse Intl Condensed" (fallback: "Barlow Condensed"), Bold, 36-44pt, `APTEAN_NAVY`/`BLACK` (content) or `WHITE` (on dark/gradient). Body: "Suisse Intl" (fallback: "Arial"), Regular, 16-20pt, `CHARCOAL`. Footer: 9-10pt, `WHITE`. Set `run.font.name` to primary font.

## PPTX Slide Dimensions

```python
prs = Presentation()
prs.slide_width  = Inches(13.333)  # Widescreen 16:9
prs.slide_height = Inches(7.5)
```

## PPTX Footer Helper

Every slide (except section dividers) must include the Aptean footer bar. Define this reusable helper:

```python
from pptx.util import Inches, Pt, Emu
from pptx.enum.shapes import MSO_SHAPE

def add_footer(slide, slide_number, prs):
    """Add the standard Aptean footer bar to a slide."""
    sw = prs.slide_width
    bar_h = Inches(0.4)
    bar_y = prs.slide_height - bar_h

    # Dark footer bar
    bar = slide.shapes.add_shape(
        MSO_SHAPE.RECTANGLE, Emu(0), bar_y, sw, bar_h
    )
    bar.fill.solid()
    bar.fill.fore_color.rgb = FOOTER_DARK
    bar.line.fill.background()

    # "aptean" logo text (left side)
    logo_tf = slide.shapes.add_textbox(
        Inches(0.5), bar_y, Inches(2), bar_h
    ).text_frame
    logo_tf.word_wrap = False
    logo_run = logo_tf.paragraphs[0].add_run()
    logo_run.text = "aptean"
    logo_run.font.name = "Suisse Intl"
    logo_run.font.size = Pt(12)
    logo_run.font.bold = True
    logo_run.font.color.rgb = WHITE
    logo_tf.paragraphs[0].alignment = PP_ALIGN.LEFT
    logo_tf.paragraphs[0].space_before = Pt(4)

    # Confidentiality + page number (right side)
    conf_tf = slide.shapes.add_textbox(
        Inches(6), bar_y, sw - Inches(6) - Inches(0.3), bar_h
    ).text_frame
    conf_tf.word_wrap = False
    conf_run = conf_tf.paragraphs[0].add_run()
    conf_run.text = f"Company Confidential Do Not Distribute   |   {slide_number}"
    conf_run.font.name = "Suisse Intl"
    conf_run.font.size = Pt(9)
    conf_run.font.color.rgb = WHITE
    conf_tf.paragraphs[0].alignment = PP_ALIGN.RIGHT
    conf_tf.paragraphs[0].space_before = Pt(4)
```

## PPTX Gradient Helper

Section divider slides use the Aptean signature gradient (steel blue to dusty lavender to soft mauve-pink). Generate it with Pillow if available, otherwise fall back to solid navy.

```python
def make_gradient_bg(width_px=1920, height_px=1080):
    """Return gradient PNG bytes, or None if Pillow is unavailable."""
    try:
        from PIL import Image
    except ImportError:
        return None

    img = Image.new("RGB", (width_px, height_px))
    pixels = img.load()
    # Gradient stops: 0% #477599, 40% #8276AE, 100% #C878BE
    stops = [
        (0.0,  (0x47, 0x75, 0x99)),
        (0.4,  (0x82, 0x76, 0xAE)),
        (1.0,  (0xC8, 0x78, 0xBE)),
    ]
    for x in range(width_px):
        t = x / (width_px - 1)
        # Find surrounding stops
        for i in range(len(stops) - 1):
            if stops[i][0] <= t <= stops[i + 1][0]:
                seg_t = (t - stops[i][0]) / (stops[i + 1][0] - stops[i][0])
                c0, c1 = stops[i][1], stops[i + 1][1]
                r = int(c0[0] + (c1[0] - c0[0]) * seg_t)
                g = int(c0[1] + (c1[1] - c0[1]) * seg_t)
                b = int(c0[2] + (c1[2] - c0[2]) * seg_t)
                break
        for y in range(height_px):
            pixels[x, y] = (r, g, b)

    import io
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()
```

## PPTX Slide Type Patterns

Use these patterns to build slides. Each pattern shows the python-pptx code structure. Substitute `{{PLACEHOLDER}}` values with actual content.

### 1. Title Slide

White background, large bold title, project name subtitle, footer.

```python
from pptx.enum.text import MSO_ANCHOR

slide_layout = prs.slide_layouts[6]  # Blank layout
slide = prs.slides.add_slide(slide_layout)

# Title
title_tf = slide.shapes.add_textbox(
    Inches(0.8), Inches(1.0), Inches(11), Inches(2)
).text_frame
title_tf.word_wrap = True
title_run = title_tf.paragraphs[0].add_run()
title_run.text = "{{REPORT_TITLE}}"
title_run.font.name = "Suisse Intl Condensed"
title_run.font.size = Pt(44)
title_run.font.bold = True
title_run.font.color.rgb = BLACK

# Subtitle / tag line
sub_tf = slide.shapes.add_textbox(
    Inches(0.8), Inches(3.2), Inches(11), Inches(1)
).text_frame
sub_run = sub_tf.paragraphs[0].add_run()
sub_run.text = "{{TAG_LINE}}"
sub_run.font.name = "Suisse Intl"
sub_run.font.size = Pt(20)
sub_run.font.color.rgb = CHARCOAL

# Metadata line (date, project, scope)
meta_tf = slide.shapes.add_textbox(
    Inches(0.8), Inches(4.2), Inches(11), Inches(0.6)
).text_frame
meta_run = meta_tf.paragraphs[0].add_run()
meta_run.text = "{{FULL_DATE}}  |  {{PROJECT_NAME}}  |  {{SCOPE_SUMMARY}}"
meta_run.font.name = "Suisse Intl"
meta_run.font.size = Pt(14)
meta_run.font.color.rgb = APTEAN_BLUE

add_footer(slide, 1, prs)
```

### 2. Section Divider Slide

Gradient background (or solid navy fallback), large white title, NO footer.

```python
slide = prs.slides.add_slide(prs.slide_layouts[6])  # Blank

# Gradient background
gradient_bytes = make_gradient_bg()
if gradient_bytes:
    import io
    slide.shapes.add_picture(
        io.BytesIO(gradient_bytes),
        Emu(0), Emu(0),
        prs.slide_width, prs.slide_height,
    )
else:
    # Solid navy fallback
    bg = slide.shapes.add_shape(
        MSO_SHAPE.RECTANGLE, Emu(0), Emu(0),
        prs.slide_width, prs.slide_height,
    )
    bg.fill.solid()
    bg.fill.fore_color.rgb = APTEAN_NAVY
    bg.line.fill.background()

# Section title (lower-left quadrant)
sec_tf = slide.shapes.add_textbox(
    Inches(0.8), Inches(4.0), Inches(10), Inches(2.5)
).text_frame
sec_tf.word_wrap = True
sec_run = sec_tf.paragraphs[0].add_run()
sec_run.text = "{{SECTION_TITLE}}"
sec_run.font.name = "Suisse Intl Condensed"
sec_run.font.size = Pt(60)
sec_run.font.bold = True
sec_run.font.color.rgb = WHITE
# NO footer on section dividers
```

### 3. Content Slide

White background with title, body text, and footer. The standard workhorse slide.

```python
slide = prs.slides.add_slide(prs.slide_layouts[6])  # Blank

# Title
title_tf = slide.shapes.add_textbox(
    Inches(0.8), Inches(0.4), Inches(11.5), Inches(0.9)
).text_frame
title_run = title_tf.paragraphs[0].add_run()
title_run.text = "{{SLIDE_TITLE}}"
title_run.font.name = "Suisse Intl Condensed"
title_run.font.size = Pt(36)
title_run.font.bold = True
title_run.font.color.rgb = APTEAN_NAVY

# Body (add multiple paragraphs for bullet points)
body_tf = slide.shapes.add_textbox(
    Inches(0.8), Inches(1.5), Inches(11.5), Inches(5.0)
).text_frame
body_tf.word_wrap = True

for bullet_text in ["{{BULLET_1}}", "{{BULLET_2}}", "{{BULLET_3}}"]:
    p = body_tf.add_paragraph() if body_tf.paragraphs[0].text else body_tf.paragraphs[0]
    run = p.add_run()
    run.text = bullet_text
    run.font.name = "Suisse Intl"
    run.font.size = Pt(18)
    run.font.color.rgb = CHARCOAL
    p.space_after = Pt(8)

add_footer(slide, slide_number, prs)
```

### 4. Findings Table Slide

Table with severity-colored cells for findings.

```python
slide = prs.slides.add_slide(prs.slide_layouts[6])

# Title
title_tf = slide.shapes.add_textbox(
    Inches(0.8), Inches(0.4), Inches(11.5), Inches(0.9)
).text_frame
title_run = title_tf.paragraphs[0].add_run()
title_run.text = "{{TABLE_TITLE}}"
title_run.font.name = "Suisse Intl Condensed"
title_run.font.size = Pt(36)
title_run.font.bold = True
title_run.font.color.rgb = APTEAN_NAVY

# Table
rows = len(findings) + 1  # +1 for header
cols = 3  # Severity, Finding, Location
table_shape = slide.shapes.add_table(
    rows, cols,
    Inches(0.8), Inches(1.5), Inches(11.5), Inches(0.4 * rows)
)
table = table_shape.table

# Header row
for idx, header in enumerate(["Severity", "Finding", "Location"]):
    cell = table.cell(0, idx)
    cell.text = header
    cell.fill.solid()
    cell.fill.fore_color.rgb = LIGHT_GRAY
    for p in cell.text_frame.paragraphs:
        for r in p.runs:
            r.font.name = "Suisse Intl"
            r.font.size = Pt(11)
            r.font.bold = True
            r.font.color.rgb = APTEAN_NAVY

# Column widths
table.columns[0].width = Inches(1.5)
table.columns[1].width = Inches(7.5)
table.columns[2].width = Inches(2.5)

# Data rows -- severity cell fill uses the severity background colors
SEV_MAP = {
    "CRITICAL": (SEV_CRITICAL, SEV_CRITICAL_BG),
    "HIGH":     (SEV_HIGH, SEV_HIGH_BG),
    "MEDIUM":   (SEV_MEDIUM, SEV_MEDIUM_BG),
    "LOW":      (SEV_LOW, SEV_LOW_BG),
    "GOOD":     (SEV_GOOD, SEV_GOOD_BG),
}

for row_idx, finding in enumerate(findings, start=1):
    sev = finding["severity"].upper()
    fg, bg = SEV_MAP.get(sev, (CHARCOAL, WHITE))

    # Severity cell
    sev_cell = table.cell(row_idx, 0)
    sev_cell.text = sev
    sev_cell.fill.solid()
    sev_cell.fill.fore_color.rgb = bg
    for r in sev_cell.text_frame.paragraphs[0].runs:
        r.font.color.rgb = fg
        r.font.bold = True
        r.font.size = Pt(10)
        r.font.name = "Suisse Intl"

    # Finding cell
    table.cell(row_idx, 1).text = finding["title"]
    # Location cell
    table.cell(row_idx, 2).text = finding["location"]

    for col in range(1, 3):
        for r in table.cell(row_idx, col).text_frame.paragraphs[0].runs:
            r.font.name = "Suisse Intl"
            r.font.size = Pt(11)
            r.font.color.rgb = CHARCOAL

add_footer(slide, slide_number, prs)
```

### 5. Score Card Slide

Grid of metric shapes for executive summary KPIs.

```python
slide = prs.slides.add_slide(prs.slide_layouts[6])

# Title
title_tf = slide.shapes.add_textbox(
    Inches(0.8), Inches(0.4), Inches(11.5), Inches(0.9)
).text_frame
title_run = title_tf.paragraphs[0].add_run()
title_run.text = "Executive Summary"
title_run.font.name = "Suisse Intl Condensed"
title_run.font.size = Pt(36)
title_run.font.bold = True
title_run.font.color.rgb = APTEAN_NAVY

# Metric cards laid out in a grid (up to 4 per row)
metrics = [
    {"label": "{{LABEL}}", "value": "{{VALUE}}", "color": APTEAN_NAVY},
    # ... more metrics
]

cards_per_row = 4
card_w = Inches(2.7)
card_h = Inches(1.6)
start_x = Inches(0.8)
start_y = Inches(1.8)
gap = Inches(0.25)

for i, m in enumerate(metrics):
    col = i % cards_per_row
    row = i // cards_per_row
    x = start_x + col * (card_w + gap)
    y = start_y + row * (card_h + gap)

    # Card background
    card = slide.shapes.add_shape(
        MSO_SHAPE.ROUNDED_RECTANGLE, x, y, card_w, card_h
    )
    card.fill.solid()
    card.fill.fore_color.rgb = RGBColor(0xF8, 0xF9, 0xFB)
    card.line.color.rgb = RGBColor(0xE2, 0xE7, 0xEF)
    card.line.width = Pt(1)

    # Label
    lbl_tf = slide.shapes.add_textbox(x, y + Inches(0.15), card_w, Inches(0.35)).text_frame
    lbl_run = lbl_tf.paragraphs[0].add_run()
    lbl_run.text = m["label"].upper()
    lbl_run.font.name = "Suisse Intl"
    lbl_run.font.size = Pt(9)
    lbl_run.font.bold = True
    lbl_run.font.color.rgb = RGBColor(0x86, 0x94, 0xA1)
    lbl_tf.paragraphs[0].alignment = PP_ALIGN.CENTER

    # Value
    val_tf = slide.shapes.add_textbox(x, y + Inches(0.5), card_w, Inches(0.8)).text_frame
    val_run = val_tf.paragraphs[0].add_run()
    val_run.text = m["value"]
    val_run.font.name = "Suisse Intl"
    val_run.font.size = Pt(28)
    val_run.font.bold = True
    val_run.font.color.rgb = m["color"]
    val_tf.paragraphs[0].alignment = PP_ALIGN.CENTER

add_footer(slide, slide_number, prs)
```

### 6. Impact / Quote Slide

Black background, large centered white text.

```python
slide = prs.slides.add_slide(prs.slide_layouts[6])

# Black background
bg = slide.shapes.add_shape(
    MSO_SHAPE.RECTANGLE, Emu(0), Emu(0),
    prs.slide_width, prs.slide_height,
)
bg.fill.solid()
bg.fill.fore_color.rgb = BLACK
bg.line.fill.background()

# Centered quote text
quote_tf = slide.shapes.add_textbox(
    Inches(1.5), Inches(2.0), Inches(10), Inches(3.5)
).text_frame
quote_tf.word_wrap = True
quote_run = quote_tf.paragraphs[0].add_run()
quote_run.text = "{{QUOTE_TEXT}}"
quote_run.font.name = "Suisse Intl Condensed"
quote_run.font.size = Pt(48)
quote_run.font.bold = True
quote_run.font.color.rgb = WHITE
quote_tf.paragraphs[0].alignment = PP_ALIGN.CENTER

add_footer(slide, slide_number, prs)
```

## Report-to-Slide Mapping

Header->Title(#1), Methodology->Content(#3), Exec Summary->ScoreCard(#5), Dimension heading->Divider(#2), Findings table->Table(#4, max 8 rows/slide), Narrative->Content(#3), Recommendations->Content(#3), Takeaway->Impact(#6), Disclaimer->Content(#3).

**Order**: Title -> Methodology -> Executive Summary -> (Divider -> Findings -> Narrative) per dimension -> Recommendations -> Takeaway -> Disclaimer.

## EXECUTION STEPS (PPTX)

Steps 1-2 same as HTML. **Step 3**: Write self-contained `.py` script to `.polaris/reports/_generate_{report-slug}.py` with auto-install fallback, color constants, helpers, 16:9 Presentation, slides per mapping order. **Step 4**: Run script, verify `.pptx` created, report both paths.

**PPTX Checklist**: Only RGBColor constants (no ad-hoc hex), title slide complete, dividers use gradient (no footer), all other slides have footer, severity fills in tables, correct fonts, 16:9 dimensions, tables split at 8 rows, no Unicode issues.

**Principles**: Design system is law, evidence-based, actionable, self-contained, non-destructive (`.polaris/reports/` only), cross-platform.

## Context

$ARGUMENTS
