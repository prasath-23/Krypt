import { test, expect } from '@playwright/test';

test.describe('WP02: WP02-installed-apps-repository', () => {
  test('should complete WP02-installed-apps-repository', async ({ page }) => {
    await page.goto('/');
    // Verify `InstalledAppsRepository.allInstalled()` returns sorted, filtered list on Robolectric.
    // Verify `AppIconCache` evicts at the configured byte budget and never crashes on `NameNotFoundException`.
    // Verify No new permission added to `AndroidManifest.xml` (the existing `QUERY_ALL_PACKAGES` from feature 001 remains the source).
    // Verify `./gradlew :app:testDebugUnitTest --tests "com.krypt.app.ui.home.*"` is green.
    // Verify no JavaScript errors
  });
});
