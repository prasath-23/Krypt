# E2E Test Plan: 002-manual-lock-and-permission-clarity

## Overview

Automated E2E tests for 5 work packages.

## Test Files

| Work Package | Title | Test File |
|---|---|---|
| WP01 | WP01-permission-status-infrastructure | `WP01-wp01-permission-status-infrastructure.e2e.js` |
| WP02 | WP02-installed-apps-repository | `WP02-wp02-installed-apps-repository.e2e.js` |
| WP03 | WP03-onboarding-redesign-with-live-indicators | `WP03-wp03-onboarding-redesign-with-live-indicators.e2e.js` |
| WP04 | WP04-home-screen-manual-lock-toggle | `WP04-wp04-home-screen-manual-lock-toggle.e2e.js` |
| WP05 | WP05-navigation-rewire-and-e2e | `WP05-wp05-navigation-rewire-and-e2e.e2e.js` |

## Running Tests

```bash
# Run all E2E tests for this feature
polaris runtests --feature 002-manual-lock-and-permission-clarity

# Run with Playwright directly
npx playwright test tests/e2e/

# Run a specific work package test
npx playwright test tests/e2e/WP01-wp01-permission-status-infrastructure.e2e.js
```
