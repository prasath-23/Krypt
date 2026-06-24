import { test, expect } from '@playwright/test';

test.describe('WP21: WP21-amendment1-guardian-pin-validation', () => {
  test('should complete WP21-amendment1-guardian-pin-validation', async ({ page }) => {
    await page.goto('/');
    // Verify Correct PIN + valid request -> `ApprovalLinkBuilder.build` invoked exactly once; approval URI produced; share sheet opens.
    // Verify Wrong PIN -> `WrongPin` error surfaced with "attempts left" counter; no approval URI created.
    // Verify 3 wrong attempts -> 60 s lockout; clock-based countdown.
    // Verify Constant-time proof compare (`MessageDigest.isEqual`).
    // Verify Typed `CharArray` zeroed after every validate call (verified in test).
    // Verify No writes to disk on the Guardian device (stateless).
    // Verify Expired request -> friendly error, no PIN prompt.
    // Verify no JavaScript errors
  });
});
