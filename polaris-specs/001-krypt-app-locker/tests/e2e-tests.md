# E2E Test Plan: 001-krypt-app-locker

## Overview

Automated E2E tests for 18 work packages.

## Test Files

| Work Package | Title | Test File |
|---|---|---|
| WP01 | WP01-project-scaffold | `WP01-wp01-project-scaffold.spec.js` |
| WP02 | WP02-crypto-primitives | `WP02-wp02-crypto-primitives.spec.js` |
| WP03 | WP03-deeplink-request-approve | `WP03-wp03-deeplink-request-approve.spec.js` |
| WP04 | WP04-deeplink-pair-paired | `WP04-wp04-deeplink-pair-paired.spec.js` |
| WP05 | WP05-room-database | `WP05-wp05-room-database.spec.js` |
| WP06 | WP06-repositories-stores | `WP06-wp06-repositories-stores.spec.js` |
| WP07 | WP07-notifications | `WP07-wp07-notifications.spec.js` |
| WP08 | WP08-package-receiver | `WP08-wp08-package-receiver.spec.js` |
| WP09 | WP09-overlay-manager | `WP09-wp09-overlay-manager.spec.js` |
| WP10 | WP10-accessibility-service | `WP10-wp10-accessibility-service.spec.js` |
| WP11 | WP11-device-admin | `WP11-wp11-device-admin.spec.js` |
| WP12 | WP12-ui-scaffold-onboarding | `WP12-wp12-ui-scaffold-onboarding.spec.js` |
| WP13 | WP13-pairing-ui | `WP13-wp13-pairing-ui.spec.js` |
| WP14 | WP14-guardian-activity | `WP14-wp14-guardian-activity.spec.js` |
| WP15 | WP15-approval-consumption | `WP15-wp15-approval-consumption.spec.js` |
| WP16 | WP16-watchdog-battery-hooks | `WP16-wp16-watchdog-battery-hooks.spec.js` |
| WP17 | WP17-e2e-tests | `WP17-wp17-e2e-tests.spec.js` |
| WP18 | WP18-polish | `WP18-wp18-polish.spec.js` |

## Running Tests

```bash
# Run all E2E tests for this feature
polaris runtests --feature 001-krypt-app-locker

# Run with Playwright directly
npx playwright test tests/e2e/

# Run a specific work package test
npx playwright test tests/e2e/WP01-wp01-project-scaffold.spec.js
```
