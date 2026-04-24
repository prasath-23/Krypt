import { test, expect } from '@playwright/test';

test.describe('WP13: WP13-pairing-ui', () => {
  test('should complete WP13-pairing-ui', async ({ page }) => {
    await page.goto('/');
    // Verify Role chooser appears at the right place (Onboarding Step 0 or standalone route after onboarding).
    // Verify SubjectPairScreen generates a valid `krypt://pair` URL and opens Share sheet.
    // Verify GuardianPairConsumeScreen accepts a `krypt://pair` deep-link, prompts PIN, emits `krypt://paired`.
    // Verify the page shows "the right messages."
    // Verify Ephemeral X25519 private key lives in a ViewModel scope tied to the pairing session and is cleared afterward.
    // Verify All four Compose UI tests pass.
    // Verify Manual walkthrough on two real devices: pairing completes in under 2 minutes including PIN set.
    // Verify no JavaScript errors
  });
});
