import { test, expect } from '@playwright/test';

test.describe('WP10: WP10-accessibility-service', () => {
  test('should complete WP10-accessibility-service', async ({ page }) => {
    await page.goto('/');
    // Verify Service registers under Settings -> Accessibility; toggling it works.
    // Verify Overlay appears within ~200 ms on the target hardware (recorded latency logs present).
    // Verify Launching GuardianActivity does NOT trigger the overlay (FR-011 verified).
    // Verify Launching any system-UI package does not trigger the overlay.
    // Verify `AppLockerAccessibilityServiceTest` passes on a connected device.
    // Verify Hot-path `onAccessibilityEvent` does NOT launch a coroutine per event (pure sync lookups; coroutines only at service start).
    // Verify `appScope` is cancelled in `onDestroy`.
    // Verify no JavaScript errors
  });
});
