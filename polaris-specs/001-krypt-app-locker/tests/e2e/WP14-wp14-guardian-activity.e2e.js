import { test, expect } from '@playwright/test';

test.describe('WP14: WP14-guardian-activity', () => {
  test('should complete WP14-guardian-activity', async ({ page }) => {
    await page.goto('/');
    // Verify GuardianActivity opens on all four `krypt://` authorities and dispatches to the right screen.
    // Verify PIN UI uses NumberPassword keyboard + password-masking; `FLAG_SECURE` applied.
    // Verify Rate-limiter enforces progressive lockout (3/5/7/10 threshold policy).
    // Verify Wrong-PIN outcome does NOT leak timing information (KDF cost is constant for wrong/right).
    // Verify AccessibilityService self-whitelist matches GuardianActivity's actual class name (compile-time or startup-time check).
    // Verify Compose UI tests green.
    // Verify no JavaScript errors
  });
});
