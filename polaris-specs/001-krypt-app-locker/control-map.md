# Control Map --- Krypt

This feature has 6 distinct runtime flows that touch shared interception and crypto components. Each flow has a single, documented entry point and a bounded set of collaborators.

## Flows

| Flow | Purpose | Entry point | Key collaborators |
|------|---------|-------------|-------------------|
| **Onboarding** | Guides the Administrator through granting Accessibility + Overlay + Device-Admin privileges, pairs the Guardian device, seeds the initial locked-apps list. | `MainActivity` (launcher) | `PermissionWizardScreen`, `GuardianPairingScreen`, `LockedAppsRepository` |
| **Default-Deny + Notification** | Detects new package installs, marks them locked, and posts a Security-Alerts notification. | `PackageReceiver` (system broadcast `ACTION_PACKAGE_ADDED`) | `LockedAppsRepository`, `NotificationHelper`, `SecurityAlertsChannel` |
| **Interception / Locker Overlay** | Observes foreground-state changes, decides if the foreground package is locked, and covers it with a non-dismissible overlay. | `AppLockerAccessibilityService` | `LockedAppsRepository`, `OverlayManager` (WindowManager wrapper), `LockerOverlayView` (XML) |
| **Subject Request** | Subject taps "Ask Guardian" on the Locker Overlay; Krypt builds an unlock-request deep-link and opens the system Share sheet. | `LockerOverlayView` "Ask Guardian" action | `UnlockRequestBuilder`, `CryptoHelper` (PBKDF2 / salt generation), Android Share Intent |
| **Guardian Approval** | Guardian device opens a shared request URL; Krypt's Guardian Popup validates the PIN, builds an approval URL, and re-shares. | `GuardianActivity` (deep-link `myapp://request?...`) | `UnlockRequestParser`, `CryptoHelper` (PBKDF2 verify + AES-256 encrypt), `ApprovalLinkBuilder`, Share Intent |
| **Subject Unlock Consumption** | Subject device opens an approval URL; Krypt verifies, creates a time-boxed Locker Session, dismisses the overlay. | `GuardianActivity` (deep-link `myapp://approve?...`) OR `ApprovalConsumerActivity` | `ApprovalLinkParser`, `CryptoHelper` (AES-256 decrypt), `LockerSessionStore`, `OverlayManager.dismiss()` |

## Shared Dependencies

| Component | Used by | Path (planned) |
|-----------|---------|----------------|
| `LockedAppsRepository` | Onboarding, Default-Deny, Interception | `data/LockedAppsRepository.kt` |
| `CryptoHelper` | Subject Request, Guardian Approval, Subject Unlock Consumption | `crypto/CryptoHelper.kt` |
| `NotificationHelper` + `SecurityAlertsChannel` | Default-Deny, Guardian Approval (optional echo) | `notifications/NotificationHelper.kt` |
| `OverlayManager` | Interception, Subject Unlock Consumption | `overlay/OverlayManager.kt` |
| `LockerSessionStore` | Interception (read), Subject Unlock Consumption (write) | `data/LockerSessionStore.kt` |
| `DeepLinkScheme` (URI parse/build) | Subject Request, Guardian Approval, Subject Unlock Consumption | `deeplink/DeepLinkScheme.kt` |

## Deep-Link Scheme Summary

- **Request:** `krypt://request?v=1&req=<reqId>&app=<pkg>&salt=<b64>&proof=<b64>&iat=<epoch>`
- **Approval:** `krypt://approve?v=1&req=<reqId>&data=<b64-AES256GCM>&iat=<epoch>`

Scheme + host are the same (`krypt://`) for easy manifest `<intent-filter>` registration; the path component distinguishes `request` from `approve`.

## Whitelist (to avoid self-interception)

The `AppLockerAccessibilityService` must NOT lock `GuardianActivity` when it comes foreground --- otherwise a Guardian whose own device is locked down cannot approve incoming requests. This is enforced by a package + activity-name whitelist inside the service.
