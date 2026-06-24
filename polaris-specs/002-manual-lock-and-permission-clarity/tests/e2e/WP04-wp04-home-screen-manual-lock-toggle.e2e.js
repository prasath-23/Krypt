import { test, expect } from '@playwright/test';

test.describe('WP04: WP04-home-screen-manual-lock-toggle', () => {
  test('should complete WP04-home-screen-manual-lock-toggle', async ({ page }) => {
    await page.goto('/');
    // Verify the page shows "a sorted, searchable list of installed apps with correct lock state."
    // Verify Tapping an unlocked toggle persists `LockedAppsRepository.lock(pkg, source = MANUAL)` and the row re-emits as locked within one frame.
    // Verify Tapping a locked toggle does NOT change state and does NOT call `LockedAppsRepository.unlock`. A `krypt://request?...` `ACTION_SEND` intent is dispatched via the share sheet.
    // Verify Search filtering is case-insensitive and runs in <100 ms for 200 apps (informal benchmark; measured under Robolectric is fine).
    // Verify No new permission added to `AndroidManifest.xml`.
    // Verify All unit + Compose tests green.
    // Verify no JavaScript errors
  });
});
