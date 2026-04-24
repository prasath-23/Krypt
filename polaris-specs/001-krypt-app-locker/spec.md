# Feature Spec --- Krypt: Zero-Trust Offline App Locker

**Feature:** `001-krypt-app-locker`
**Mission:** software-dev
**Status:** draft
**Target branch:** `main`

---

## 1. Summary

Krypt is a native Android application that enforces a **default-deny** policy on every app installed on a device. Newly-installed apps are automatically locked. When a locked app is launched, Krypt intercepts the launch and presents a Locker Screen that can only be dismissed by a cryptographically-signed approval from a *Guardian* --- a trusted second party who holds the unlock PIN on their own device. Request and approval messages travel as encrypted deep-link URLs through any off-device channel the two parties share (WhatsApp, SMS, email, in-person QR), so **the Subject device never needs an internet connection** to complete an unlock.

## 2. Problem & Motivation

Existing consumer app-locker products fall into two camps:

1. **On-device PIN-only lockers.** The PIN lives on the same device it protects. A Subject (minor, rehab patient, focus-seeker) who is sufficiently motivated will observe, coerce, or social-engineer the PIN out of its holder, and the defence collapses.
2. **Cloud-backed family-control products.** They work, but they (a) require the device to be online to validate unlocks, (b) leak telemetry about which apps are used and when, and (c) tie the whole feature to a vendor account that can lapse or be compromised.

Krypt eliminates both failure modes by moving the unlock authority to a *physically separate device* held by a Guardian, and by using asymmetric-knowledge cryptography rather than a network call to transmit the decision. The result is a locker that is (i) genuinely offline, (ii) immune to PIN observation on the Subject device (the PIN is never typed on the Subject device), and (iii) shipping-no-telemetry by manifest --- there is no `INTERNET` permission to audit.

## 3. User Roles

| Role | Description |
|------|-------------|
| **Subject** | Owns the device that Krypt runs on. May or may not have consented (family, clinical, or voluntary-focus contexts). Does **not** know the unlock PIN. |
| **Guardian** | Trusted second party (parent, sponsor, therapist, accountability partner) who holds the unlock PIN on their own device and approves unlock requests. |
| **Administrator** | Whoever performs the initial Krypt installation and permission grants on the Subject device. Often the same person as the Guardian, but not always. |

## 4. User Scenarios

### US-1 --- First-time setup
1. Administrator installs Krypt on the Subject device.
2. Krypt walks the Administrator through granting three device-level privileges: Accessibility Service, Draw-over-other-apps, and Device-Admin.
3. Krypt pairs with a Guardian device by exchanging a one-time setup code. The Guardian establishes the master PIN on *their own* device (not the Subject's).
4. From that moment forward, every app already installed is treated as locked by default.

### US-2 --- A new app is installed
1. Any actor (Subject, Administrator, system updater, sideload) installs a new package.
2. Krypt detects the install immediately and adds the package to the locked list.
3. A local notification on the Subject device, via a "Security Alerts" channel, announces: *"New App Protected --- `com.example.app` has been locked by default."*
4. Launching the new app opens the Locker Screen instead of the app's own UI.

### US-3 --- Subject launches a locked app
1. Subject taps a locked app from the launcher.
2. Within 200 ms (before any of the target app's UI renders), a fullscreen Locker Screen covers the app.
3. The Locker Screen displays: app name and icon, an *Ask Guardian* button, and (if relevant) a cooldown timer.
4. The Locker Screen cannot be dismissed by Back, Home, Recents, status-bar, or the notification shade.
5. Tapping *Ask Guardian* opens the system Share sheet pre-populated with a request deep-link URL.
6. Subject shares the URL via any messenger/SMS/email to the Guardian.

### US-4 --- Guardian approves
1. Guardian receives the deep link on their own device (any app, any transport).
2. Tapping the link opens Krypt's Guardian Popup on the Guardian device.
3. The Popup shows the requesting Subject's display name, the target app, and a PIN entry field.
4. Guardian types the PIN. Krypt validates the PIN against the cryptographic proof embedded in the URL.
5. On valid PIN, Krypt generates an approval deep-link URL, encrypted under a key derived from the PIN-verified material, and opens the system Share sheet to send it back to the Subject.

### US-5 --- Subject consumes the approval
1. Subject taps the approval link in their messenger.
2. Krypt on the Subject device decrypts the payload, verifies it matches the outstanding request, and dismisses the Locker Screen for a bounded time window (default 15 minutes).
3. When the window expires, the next foreground event on the app re-triggers the Locker Screen.

### US-6 --- Uninstall attempt
1. Subject attempts to uninstall Krypt from Settings.
2. Because Krypt holds Device-Admin privilege, the OS refuses the uninstall until Device-Admin is revoked first.
3. Revoking Device-Admin requires deep navigation of Settings (consumer-grade friction, not cryptographic block).

### US-7 --- Fully offline operation
1. Both Subject and Guardian devices have airplane mode on.
2. All of US-3, US-4, US-5 still complete as long as the two parties can exchange a text URL (in-person copy, Bluetooth-share, offline messenger, printed QR, etc.).

## 5. Functional Requirements

Each requirement is stated as a testable capability.

| ID | Requirement |
|----|-------------|
| FR-001 | The system MUST intercept the launch of any locked application and render the Locker Screen before the target app's UI becomes visible. |
| FR-002 | The Locker Screen MUST NOT be dismissible by Back, Home, Recents, notification-shade, status bar, or rotation. |
| FR-003 | The system MUST automatically classify every newly-installed package as locked, regardless of install source. |
| FR-004 | The system MUST post a local notification via a user-visible "Security Alerts" channel within 500 ms of each new install being locked. The notification body MUST contain the locked package name. |
| FR-005 | The system's manifest MUST NOT declare `android.permission.INTERNET`. This MUST remain auditable by third-party manifest inspection. |
| FR-006 | The system MUST register a custom deep-link URI scheme that routes both *unlock-request* URLs and *unlock-approval* URLs from any third-party transport app. |
| FR-007 | Unlock-request URLs MUST be self-contained: all data required to validate a Guardian's PIN MUST travel in the URL. No server lookup, no background sync. |
| FR-008 | Guardian PIN verification MUST apply a slow key-derivation function with a tuned work factor of at least 300,000 PBKDF2-HMAC-SHA256 iterations (or equivalent), such that a correct or incorrect PIN check takes at least 250 ms of CPU time on target-class hardware. |
| FR-009 | Unlock-approval URLs MUST carry their payload encrypted under an AES-256 key derived from the PIN-verified material, such that an observer of the URL cannot read the grant without the PIN. |
| FR-010 | The interception layer MUST continue to function after 24 hours of screen-off idle, surviving standard OEM battery-optimisation policies (Doze, App Standby). |
| FR-011 | The system MUST whitelist its own Guardian Popup activity from its own interception, so Guardians can approve requests even on locked-down devices. |
| FR-012 | Uninstalling the app MUST require Device-Admin to be revoked first. |
| FR-013 | Each unlock grant MUST be time-boxed. After the grant window expires, the next foreground event on the target app MUST re-trigger the Locker Screen. |
| FR-014 | The Subject MUST be able to initiate an unlock request without typing any PIN, and the system MUST compose a shareable deep-link payload for the Subject to send. |
| FR-015 | The system MUST reject any approval URL whose embedded request-ID does not match an outstanding request, or whose decryption fails. |
| FR-016 | The system MUST provide an internal mock/seam for the "locked-apps database" such that foundational code can be exercised before a persistent store is wired up. |

## 6. Success Criteria

All criteria are measurable and technology-agnostic.

| ID | Criterion |
|----|-----------|
| SC-001 | 95th-percentile latency from foreground-change event to Locker Screen visible ≤ 200 ms on Pixel-7-class hardware. |
| SC-002 | Third-party network-egress audit over a 72-hour soak (covering US-1 through US-7) observes zero outbound packets from the app UID. |
| SC-003 | Measured PIN-check CPU cost ≥ 250 ms on target hardware, correct or incorrect. |
| SC-004 | After 24 hours of screen-off idle on at least three distinct OEM devices (Pixel/Google, Samsung, Xiaomi), the Locker Screen still appears on the next launch of a locked app. |
| SC-005 | In a soak test installing 20 arbitrary new packages, 20/20 are automatically locked and 20/20 produce a Security-Alerts notification. |
| SC-006 | A direct uninstall attempt via system Settings is blocked until Device-Admin is manually revoked. |
| SC-007 | Median end-to-end Subject-request → Guardian-approve → Subject-unlock round-trip ≤ 60 seconds when both parties are active on-device. |
| SC-008 | The shipped APK's manifest, inspected by `aapt dump permissions`, contains zero occurrences of `INTERNET`. |

## 7. Key Entities

| Entity | Attributes |
|--------|------------|
| **Locked App Record** | package name, display name, icon reference, lock state (`locked` / `unlocked-until-ts`), lock source (`default-deny` / `manual`), last-launch-attempt-ts |
| **Unlock Request** | request-id, target-package, random salt, Guardian-PIN proof, issued-at-ts, expiry-ts, URL-encoded form |
| **Unlock Approval** | request-id reference, validity-window, AES-256-GCM-encrypted payload, issued-at-ts, URL-encoded form |
| **Guardian Pairing** | guardian display name, public salt (for PIN derivation), pairing-ts |
| **Locker Session** | active grant, target package, expiry-ts |

## 8. Out of Scope (v1 Foundation)

- Biometric unlock on the Guardian side (PIN-only for v1)
- QR-code or NFC Guardian onboarding (deep-link setup only for v1)
- Anti-tamper self-destruct / wipe-on-tamper
- Multi-Guardian quorum approvals ("2-of-3 Guardians must approve")
- Device-Owner-tier uninstall lock (regular Device-Admin only)
- Scheduled time-based allowlists (e.g., "auto-unlocked on weekends")
- Usage statistics or reporting UI
- PIN recovery / Guardian replacement flow
- Play Store compliance work (Krypt's Accessibility Service usage triggers Play Store review; v1 assumes sideload distribution)

## 9. Assumptions

- Both Subject and Guardian have at least one app capable of rendering and clicking URL text (messenger, SMS, email).
- Subject is non-adversarial or consensually using the locker (family-safety, rehab, focus-seeker). Krypt does **not** defend against a rooted device, an unlocked bootloader, or a Subject who can reflash the OS.
- Subject device is not enterprise-provisioned via an MDM Device Owner (regular Device-Admin coexists with personal-device MDMs but not with Device-Owner provisioning).
- Android 10 (API 29) or newer on the Subject device.
- The Guardian memorises their PIN; a written-down PIN is out of the threat model.
- A request URL leaked to a third party is considered "captured adversarially"; Krypt's security budget assumes the attacker can brute-force offline against FR-008's work factor.

## 10. Risks & Deferred Decisions

- [NEEDS CLARIFICATION: Subject-Guardian pairing UX --- likely a one-time pairing deep-link with TOFU trust, but pairing-flow details are under-specified. Decision deferred to Phase 1 data-model.]
- [NEEDS CLARIFICATION: Guardian PIN rotation semantics --- proposing that rotating the PIN invalidates every outstanding request (new PIN = new salt = old proofs uncomputable). To be confirmed in plan.]
- OEM-specific battery-whitelist screens (Xiaomi "Protected Apps", Huawei "Launch Manager", Oppo "Startup Manager") cannot be fully automated and may require user-guided Settings deep-links. This is a *deployment* risk, not a spec risk.
- Accessibility-Service usage invokes Google Play Store review for non-disability use cases. Distribution strategy (sideload vs. Play Store with waiver) is a business decision outside this spec.

---

_Generated via `/polaris.specify` on 2026-04-24. Next step: `/polaris.plan`._
