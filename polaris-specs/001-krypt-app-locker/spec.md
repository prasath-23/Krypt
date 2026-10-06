# Feature Spec --- Krypt: Zero-Trust Offline App Locker

**Feature:** `001-krypt-app-locker`
**Mission:** software-dev
**Status:** accepted (Amendment 1 in flight - WP19..WP23; Amendments 2 and 3 below)
**Target branch:** `main`

---

## Amendment 3 (2026-09-27) - Guardian-Chosen Access Time and Every-Day Allowances

**What changed:**

1. **Krypt's Home list shows a Guardian unlock.** Before this, an app unlocked by the Guardian still showed "Locked": the list read only the lock setting, and an approval never changes that. It grants temporary access, which lives in `LockerSessionStore`.
   - Each row now says what applies right now: "Locked"; "Unlocked · N min left", with a live countdown and a "Lock now" button that ends the unlock early; "Daily: N of M min left today", "Time's up today" or "Daily time paused", with an "End daily time" button.
   - The lock switch stays on while an unlock or a daily rule runs, because the app locks again by itself.
2. **The Guardian chooses what an approval allows.** The approval screen asks what to allow:
   - **One time:** 15 min, 30 min, 1 hour, 2 hours, or typed minutes (1 to 1440). The unlock lasts that long, exactly as before. 15 minutes stays the default.
   - **Every day:** up to N minutes a day (the same choices) for D days (3, 7, 14, 30, or typed days, 1 to 365), starting today. The choice travels offline, inside the encrypted approval link (`contracts/approve.md`).
   - A request link says whether the child's phone understands every-day approvals (`caps=daily`, `contracts/request.md`); the Guardian's screen offers "Every day" only then.
3. **Every-day rules on the child's phone.**
   - The app opens without asking while today's time lasts.
   - When today's time is used up, the app is blocked until midnight. The lock screen says so, and "Ask Guardian" still works, for extra one-time time.
   - After the rule's last day, the app asks every time again.
   - A new every-day approval for the same app replaces the old rule, and time already used today still counts.
   - One-time time never counts against the daily time: it is extra.
   - Locking the app again (a reinstall, or the Home toggle) ends its rule, as it ends a grant.
4. **How daily time is counted.** Krypt still cannot read the screen. It counts from what the accessibility service already reports: an app's daily time runs while it is the app in front, the screen is on and unlocked, and no one-time unlock is running.
   - Time is measured on the monotonic clock, so changing the clock adds none.
   - Usage is saved every 30 s and whenever counting stops, so a crash loses at most 30 s.
   - Usage survives restarts and reboots, because it is kept per calendar day.
   - If today's time runs out while the app is open, it is blocked at that moment.
5. **The calendar day is protected against date changes** (`TrustedDayClock`):
   - Daily time only works while "Set time automatically" is on; otherwise it is paused, and the app is locked. An every-day approval tapped while it is off is refused before it is used (`ClockNotTrusted`), so it can be tapped again.
   - Within a boot, the day runs on the monotonic clock from an anchor. A date changed by hand, which needs automatic time off, never moves it, even after automatic time is back on. A time change while automatic time is on is a network correction, and is followed. One that arrives within 10 s of automatic time being switched on is ignored, because the broadcast for a change by hand can lag behind that switch.
   - After a reboot the day can't go backwards (a high-water mark), so a used-up day can't come round again.
   - Days are counted in the time zone the device had when the rule was approved, so changing the time zone makes no extra day.
   - **Residual risk:** offline, a child who sets the date forward, turns automatic time back on and reboots can use later days' time early. Each day's allowance still counts once, and the rule still ends on its last day.
6. **Known limitations of counting without screen access:**
   - Picture-in-picture and background audio aren't counted.
   - In split screen only the app opened last is counted.
   - An app kept in picture-in-picture past its time keeps playing until its next window change (as with a one-time unlock).
   - If Krypt's accessibility service is bound again (for example after its process restarts), it learns which app is in front - to block or count it - only at the next window change.
7. **Storage.** Room schema 2 adds `daily_allowances` (one rule per app) and `daily_usage` (minutes used per app per day) through a migration that only adds tables. Old rules and usage older than 35 days are pruned, by the trusted day; usage on a day an app's current rule covers is never pruned.

**FRs touched:**
- FR-013: a grant lasts as long as the Guardian chose, 1 minute to 24 hours (15 minutes by default).
- FR-037 (new): the Guardian chooses one-time or every-day access when approving.
- FR-038 (new): an every-day rule allows up to N minutes per calendar day for D days, counted only while the app is in use, blocking the app when the day's time runs out.
- FR-039 (new): daily time is paused while automatic date & time is off, and a date change can't give extra days.
- FR-040 (new): Krypt's Home list shows each app's current access and lets the Guardian end an unlock or a daily rule early.

---

## Amendment 2 (2026-09-26) - Block Locked Apps Instead of Covering Them

**What changed:**

1. **Locked apps are blocked, not covered.** Previously a locked app launched normally and a `WindowManager` overlay (`OverlayManager`) was drawn on top of it, with the app running underneath. Now `AppLockerAccessibilityService` replaces the locked app with Krypt's own lock-screen Activity (`LockScreenActivity`) as soon as the app reaches the foreground. The overlay is removed.
   - The locked app is sent behind the home screen as the lock screen opens, so it is never directly under Krypt's screen. If Krypt's process dies (a crash, an update), Android removes the lock screen and the home screen shows, not the app.
   - Leaving the lock screen (Back, or "Go to home screen") always goes to the home screen, never back into the locked app.
   - Re-opening the app by any route (launcher, Recents, a notification) brings the lock screen back.
2. **Access comes only from the Guardian.** The lock screen's "Ask Guardian" button sends the `krypt://request` link. When the Subject taps the Guardian's approval link, the app opens automatically and stays usable for the grant window. There is no PIN entry on the lock screen, so FR-018 still holds.
3. **Requests are recorded when issued.** `UnlockRequestIssuer` now saves the `OutstandingRequest` row at the moment it builds the request link. Before this change nothing saved the row, so `ApprovalConsumer` rejected every approval as `UnmatchedRequest`.
4. **The request link carries the PBKDF2 iteration count.** `krypt://request` gains `kdfIter`, the iteration count the Subject calibrated at PIN setup. The Guardian now derives the key with that count. Before, it used its own local setting (300,000 by default), which rejected the correct PIN whenever the Subject had calibrated higher. See `contracts/request.md`.
5. **Some packages are never blocked,** even if they appear in the locked list: the default home app, enabled keyboards, and the default dialer. Blocking the home app would bounce between Home and the lock screen forever. Blocking a keyboard would stop typing in every app. Blocking the dialer would stop incoming calls from being answered.
6. **New installs are locked reliably.** Android 8+ never delivered `ACTION_PACKAGE_ADDED` to the manifest-declared `PackageReceiver`, so no new install was ever auto-locked. `NewInstallLocker` now handles it instead:
   - A receiver registered at runtime by the accessibility service locks new installs as they happen.
   - A catch-up scan runs whenever the service connects. It locks any app installed after Krypt that was missed. It skips apps whose lock row was written after they were installed (already locked, or unlocked by the Guardian on purpose).
   - A reinstall counts as a new install.
   - The filter is the same one the Home list uses (FR-036).
7. **Grants survive process restarts, can't be stretched or revived, and end on time.**
   - Grants are timed on the monotonic clock (`elapsedRealtime`), so changing the date neither extends a grant nor brings an ended one back.
   - `LockerSessionStore` saves each grant on that clock, tagged with the boot it belongs to (`Settings.Global.BOOT_COUNT`). When Krypt's process restarts in the same boot, it gets back exactly the time it had left. Before this, an approved app re-locked whenever Krypt's process restarted.
   - A reboot restarts the monotonic clock and ends every grant, so the Subject asks again. So does a device that doesn't report a boot count. A grant is never restored from the wall clock: setting the date back and restarting could otherwise revive any old grant, again and again.
   - An app that is still open when its grant ends is blocked at that moment (FR-013), not at its next window change.
   - Locking an app again (a reinstall, or the Home toggle) ends any grant it still has, including across a restart.
   - The `unlock_grants` rows remain the record of which request authorised each grant. They are no longer read to decide access.
8. **The Guardian PIN is harder to guess.**
   - Both PIN screens share a persistent `PinAttemptLimiter`: the Krypt entry screen and the Guardian approval screen.
   - The 3rd wrong PIN locks for 30 s, the 4th-5th for 2 min, the 6th-7th for 10 min, and every later one for an hour.
   - Before this, the entry screen had no limit, and the approval screen's limit reset whenever it was reopened, so the Subject could guess through their own request link.
   - An attempt counts from the moment its (slow, PBKDF2) check starts, and it is on disk before the check runs. Closing or killing Krypt mid-check can't make a guess free. A correct PIN clears it, and so does finding that no PIN is set up yet.
   - Lockouts are timed on the monotonic clock. A reboot during a lockout starts it over, because the wall clock can be moved forward to end it early.
   - Leaving Krypt (Home button, Recents, screen off) locks its Home Screen again. Before this, it stayed unlocked until the process died.
9. **Links that aren't tappable can still be used.** Many messaging apps only make `https://` links tappable. The shared messages now say what to do if the link doesn't open, and there are three fallbacks:
   - "Paste link" (Krypt's onboarding and PIN screens) opens a copied request or approval.
   - "Paste approval link" on the lock screen opens a copied approval.
   - "Open in Krypt" appears as a share target and a text-selection action.
10. **Legacy pairing links removed.** `krypt://pair` and `krypt://paired` are no longer handled, and their dead screens are deleted. A crafted link could reach a screen that overwrote this device's PBKDF2 iteration setting, which would break the PIN and every approval. It could also crash Krypt below API 33.
11. **Smaller fixes.**
    - The missing `VIBRATE` permission is added; the FR-021 unlock haptic silently failed without it.
    - A proper "Krypt protection is off" alert replaces the health check's misuse of "New App Protected".
    - Starting the watchdog from the background can no longer crash Krypt on Android 12+.
    - The Home list refreshes on resume.

**FRs touched:**
- FR-001: the locked app must not be usable. The lock screen replaces it instead of covering it.
- FR-002: the lock screen may be closed, but closing it only ever leads to the home screen.
- FR-003 / FR-004: new installs are now actually locked and announced.
- FR-007: the request link now includes `kdfIter`.
- FR-013: grants expire even while their app is open. They survive process restarts but not reboots.
- FR-021: the haptic now works.

`SYSTEM_ALERT_WINDOW` is retained because it lets Krypt start its lock screen from the background.

---

## Amendment 1 (2026-04-24) - Silent-Unlock, Guardian-Sets-PIN-On-Subject-Device

**What changed (executive summary):**

1. **Setup flow inverted.** The Guardian no longer establishes the PIN on *their own* device. Instead, at first-time setup the Guardian physically holds the Subject's device, types the PIN (e.g. `8888`) into a PIN-setup screen on the Subject device, and taps Save. The Subject device computes `MasterKey = PBKDF2(PIN, random_salt, >=300,000 iters)` and stores `{salt, MasterKey, pinProof = HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")}` in Android-Keystore-backed `EncryptedSharedPreferences`. The typed PIN chars are then zeroed from memory. The Subject never learns the PIN.
2. **X25519 pairing removed.** The `krypt://pair` / `krypt://paired` exchange (WP04) is no longer part of the live flow - the Guardian has no persistent device-bound identity. A Guardian is "whoever has the WhatsApp thread and knows the shared PIN." The X25519 primitive in WP02 remains compiled but unused.
3. **Request URL now carries a PIN proof.** The Subject device embeds `pinProof` (not `K_pair`-derived material) in `krypt://request?...` so the Guardian can locally verify a typed PIN against the bundled proof before approving.
4. **Approval encryption keyed from PIN, not K_pair.** The Guardian recomputes `MasterKey = PBKDF2(PIN_typed, salt_from_url, >=300k)` on-the-fly, derives `K_req = HKDF(MasterKey, "krypt/v1/approve", req || nonce)`, and encrypts with AES-256-GCM. The Subject device already holds `MasterKey` in Keystore, so it can silently decrypt - **no PIN keypad ever appears on the Subject device during unlock consumption**.
5. **Round-trip TTL tightened to 5 minutes.** `OutstandingRequest.ttlSeconds` default changes from 1800 to 300. Single-use enforcement (atomic `consumed` flip) is retained from the original WP15 design.
6. **Unlock UX is silent.** On successful approval consumption, the Subject device shows a 500 ms green flash + toast + haptic and dismisses the overlay. No input fields.

**Why:** Removing the Subject's ability to enter a PIN eliminates the social-engineering and coercion surface that consumer-grade lockers have. Removing the X25519 pairing step keeps the Guardian device completely stateless. The 5-minute TTL shortens the replay window for captured URLs.

**Scope of the amendment:** Five new work packages `WP19..WP23`. Work packages `WP01, WP02 (X25519 retained but unused), WP05, WP07, WP08, WP09, WP10, WP11, WP12, WP16..WP18` are unchanged and remain in `done`. Work packages `WP04 (X25519 pairing), WP13 (pairing UI), WP14 (Guardian PIN validation), WP15 (approval consumption)` are marked superseded - their shipped code remains in the tree as historical; the amendment WPs rewrite the affected paths.

**FRs touched by this amendment:** FR-007 (proof material source), FR-009 (key source), FR-013 (TTL default), new FR-017..FR-021 added below.

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

### US-1 --- First-time setup (Amendment 1)
1. Administrator installs Krypt on the Subject device.
2. Krypt walks the Administrator through granting three device-level privileges: Accessibility Service, Draw-over-other-apps, and Device-Admin.
3. The Administrator hands the device to the Guardian. Krypt shows a "Set Guardian PIN" screen. The Guardian types a PIN (e.g. `8888`) and taps Save.
4. The Subject device derives `MasterKey = PBKDF2(PIN, random_salt, >=300,000)` and stores `{salt, MasterKey, pinProof}` in Android-Keystore-backed `EncryptedSharedPreferences`. The typed PIN chars are immediately zeroed.
5. The Guardian returns the device to the Subject. The Subject never learns the PIN.
6. From that moment forward, every app already installed is treated as locked by default.

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

### US-5 --- Subject consumes the approval (Amendment 1 - silent)
1. Subject taps the approval link in their messenger.
2. Krypt on the Subject device loads the stored `MasterKey` from Keystore-backed storage and silently decrypts the approval payload in the background. **No PIN keypad is shown to the Subject.**
3. The Subject device verifies the payload `req` matches an outstanding `OutstandingRequest` row with `consumed=false`, atomically flips `consumed=true`, and inserts an `UnlockGrant` row.
4. The overlay flashes green for 500 ms, triggers a short haptic, shows a toast "Unlocked by Guardian until HH:MM", and is dismissed.
5. When the grant window expires (default 15 minutes), the next foreground event on the app re-triggers the Locker Screen.

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
| FR-007 | Unlock-request URLs MUST be self-contained: all data required to validate a Guardian's PIN MUST travel in the URL. **Amendment 1:** the validation material is a `pinProof = HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")` embedded alongside the random salt used at setup. No server lookup, no background sync. |
| FR-008 | Guardian PIN verification MUST apply a slow key-derivation function with a tuned work factor of at least 300,000 PBKDF2-HMAC-SHA256 iterations (or equivalent), such that a correct or incorrect PIN check takes at least 250 ms of CPU time on target-class hardware. |
| FR-009 | Unlock-approval URLs MUST carry their payload encrypted under an AES-256-GCM key derived from the PIN-verified material, such that an observer of the URL cannot read the grant without the PIN. **Amendment 1:** the key material is `K_req = HKDF-SHA-256(MasterKey, salt="krypt/v1/approve", info=req \|\| nonce, 32)`. `MasterKey` is the same on both devices - computed once on the Subject device at setup and recomputed on-the-fly on the Guardian device from the typed PIN + `salt` in the URL. |
| FR-010 | The interception layer MUST continue to function after 24 hours of screen-off idle, surviving standard OEM battery-optimisation policies (Doze, App Standby). |
| FR-011 | The system MUST whitelist its own Guardian Popup activity from its own interception, so Guardians can approve requests even on locked-down devices. |
| FR-012 | Uninstalling the app MUST require Device-Admin to be revoked first. |
| FR-013 | Each unlock grant MUST be time-boxed. After the grant window expires, the next foreground event on the target app MUST re-trigger the Locker Screen. |
| FR-014 | The Subject MUST be able to initiate an unlock request without typing any PIN, and the system MUST compose a shareable deep-link payload for the Subject to send. |
| FR-015 | The system MUST reject any approval URL whose embedded request-ID does not match an outstanding request, or whose decryption fails. |
| FR-016 | The system MUST provide an internal mock/seam for the "locked-apps database" such that foundational code can be exercised before a persistent store is wired up. |
| FR-017 | (Amendment 1) The system MUST provide an on-device PIN-setup flow where the Guardian types the PIN on the Subject device once at setup. The typed PIN characters MUST NOT be written to disk, logged, or displayed readably after submission, and MUST be zeroed from memory immediately after the `MasterKey` is derived. |
| FR-018 | (Amendment 1) The Subject device MUST NOT prompt for any PIN at any point during approval consumption. Decryption MUST use `MasterKey` already persisted in `EncryptedSharedPreferences` from setup. |
| FR-019 | (Amendment 1) `OutstandingRequest.ttlSeconds` default MUST be 300 seconds (5 minutes) from request issuance to approval consumption. Requests past TTL MUST be rejected with a clear user-facing error. |
| FR-020 | (Amendment 1) Each `OutstandingRequest` MUST be single-use. Successful consumption MUST atomically flip `consumed=true` such that a second consume attempt on the same approval URL returns `UnmatchedRequest` (not success). |
| FR-021 | (Amendment 1) On successful silent approval consumption, the overlay MUST confirm the unlock with a visual cue (>=500 ms green flash or equivalent accessible affordance), a short haptic pulse, and a toast naming the target app. |

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
- Weekday or time-of-day schedules (e.g., "weekends only", "not after 21:00"). *Amendment 3 brings every-day allowances - N minutes per calendar day for D days - into scope; schedules stay out.*
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

- **RESOLVED --- Subject-Guardian pairing UX:** Implemented as a one-time pairing deep-link (`krypt://pair?...`) with TOFU trust. Subject scans/receives the pairing URL generated by the Guardian device; Subject's device responds with `krypt://paired?...`. Full details in WP04.
- **RESOLVED --- Guardian PIN rotation semantics:** Rotating the PIN invalidates all outstanding requests because the new PIN produces a different `K_pair`; old approval payloads encrypted under the old `K_req` (derived from old `K_pair`) fail AES-GCM tag verification. This is the desired behaviour and is enforced by WP03 `ApprovalConsumer`. Confirmed in plan.
- OEM-specific battery-whitelist screens (Xiaomi "Protected Apps", Huawei "Launch Manager", Oppo "Startup Manager") cannot be fully automated and may require user-guided Settings deep-links. This is a *deployment* risk, not a spec risk.
- Accessibility-Service usage invokes Google Play Store review for non-disability use cases. Distribution strategy (sideload vs. Play Store with waiver) is a business decision outside this spec.

---

_Generated via `/polaris.specify` on 2026-04-24. Next step: `/polaris.plan`._
