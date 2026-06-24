# E2E Test Plan: 001-krypt-app-locker

## Overview

Automated E2E tests for 23 work packages.

## Test Files

| Work Package | Title | Test File |
|---|---|---|
| WP01 | WP01-project-scaffold | `WP01-wp01-project-scaffold.e2e.js` |
| WP02 | WP02-crypto-primitives | `WP02-wp02-crypto-primitives.e2e.js` |
| WP03 | WP03-deeplink-request-approve | `WP03-wp03-deeplink-request-approve.e2e.js` |
| WP04 | WP04-deeplink-pair-paired | `WP04-wp04-deeplink-pair-paired.e2e.js` |
| WP05 | WP05-room-database | `WP05-wp05-room-database.e2e.js` |
| WP06 | WP06-repositories-stores | `WP06-wp06-repositories-stores.e2e.js` |
| WP07 | WP07-notifications | `WP07-wp07-notifications.e2e.js` |
| WP08 | WP08-package-receiver | `WP08-wp08-package-receiver.e2e.js` |
| WP09 | WP09-overlay-manager | `WP09-wp09-overlay-manager.e2e.js` |
| WP10 | WP10-accessibility-service | `WP10-wp10-accessibility-service.e2e.js` |
| WP11 | WP11-device-admin | `WP11-wp11-device-admin.e2e.js` |
| WP12 | WP12-ui-scaffold-onboarding | `WP12-wp12-ui-scaffold-onboarding.e2e.js` |
| WP13 | WP13-pairing-ui | `WP13-wp13-pairing-ui.e2e.js` |
| WP14 | WP14-guardian-activity | `WP14-wp14-guardian-activity.e2e.js` |
| WP15 | WP15-approval-consumption | `WP15-wp15-approval-consumption.e2e.js` |
| WP16 | WP16-watchdog-battery-hooks | `WP16-wp16-watchdog-battery-hooks.e2e.js` |
| WP17 | WP17-e2e-tests | `WP17-wp17-e2e-tests.e2e.js` |
| WP18 | WP18-polish | `WP18-wp18-polish.e2e.js` |
| WP19 | WP19-amendment1-ondevice-pin-setup | `WP19-wp19-amendment1-ondevice-pin-setup.e2e.js` |
| WP20 | WP20-amendment1-crypto-url-rework | `WP20-wp20-amendment1-crypto-url-rework.e2e.js` |
| WP21 | WP21-amendment1-guardian-pin-validation | `WP21-wp21-amendment1-guardian-pin-validation.e2e.js` |
| WP22 | WP22-amendment1-silent-subject-consumption | `WP22-wp22-amendment1-silent-subject-consumption.e2e.js` |
| WP23 | WP23-amendment1-e2e-tests | `WP23-wp23-amendment1-e2e-tests.e2e.js` |

## Running Tests

```bash
# Run all E2E tests for this feature
polaris runtests --feature 001-krypt-app-locker

# Run with Playwright directly
npx playwright test tests/e2e/

# Run a specific work package test
npx playwright test tests/e2e/WP01-wp01-project-scaffold.e2e.js
```
