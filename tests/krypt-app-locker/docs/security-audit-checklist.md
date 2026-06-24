# Krypt --- Security Audit Checklist

Per-release sign-off by a named reviewer. All items MUST be verified before tagging a release.

## 1. Manifest audit (SC-008)

```
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -i INTERNET
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -i NETWORK
```

Both must return **zero hits**. The Gradle task `./gradlew verifyManifest` (WP18) automates this into CI.

## 2. Binary audit

```
apktool d app-debug.apk -o apk-decompiled
grep -rE "http|okhttp|retrofit|ktor|volley" apk-decompiled/smali/com/krypt
```

Expected zero hits in Krypt-owned smali.

## 3. Crypto static review

- [ ] PBKDF2 iterations ≥ 300,000 at `KdfProvider.MIN_ITERATIONS`.
- [ ] AES mode is GCM in every `Cipher.getInstance` call (grep `AES/GCM`).
- [ ] HMAC comparisons use `MessageDigest.isEqual` (grep `isEqual`; reject `contentEquals` on MAC bytes).
- [ ] No `java.util.Random` anywhere --- only `java.security.SecureRandom` via `SecureRandomSource`.
- [ ] K_pair at rest: `EncryptedSharedPreferences` with Android Keystore master key (grep `MasterKey.KeyScheme.AES256_GCM`).

## 4. Threat-model verification

- [ ] Rooted-device attacker: out of scope, documented in spec §9.
- [ ] PIN-coerced Guardian: social, out of scope.
- [ ] Request-URL interception: defence is K_pair + AES-GCM. Confirmed in `ApprovalConsumer.consume` pipeline.
- [ ] MITM during pairing: defence is HMAC-SHA-256 over canonical MAC input under K_pair. Confirmed in `PairedReplyVerifier`.
- [ ] Replay of approval URL: defence is `OutstandingRequestRepository.consumeAndInsertGrant` atomic DB transaction.

## 5. Permission scope

Every `<uses-permission>` in AndroidManifest.xml maps to a documented FR or runtime behaviour:

- `SYSTEM_ALERT_WINDOW` --- FR-001 (overlay)
- `POST_NOTIFICATIONS` --- FR-004
- `QUERY_ALL_PACKAGES` --- locked-app enumeration + PackageReceiver display-name resolution
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` + `FOREGROUND_SERVICE_DATA_SYNC` --- WP16 watchdog
- `RECEIVE_BOOT_COMPLETED` --- rebind Accessibility after reboot
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` --- FR-010 (Doze survival)

## Sign-off

| Reviewer | Date | Commit SHA |
|---|---|---|
|  |  |  |
