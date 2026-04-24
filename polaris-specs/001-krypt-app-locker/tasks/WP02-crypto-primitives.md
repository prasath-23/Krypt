---
work_package_id: WP02
lane: "done"
dependencies: [WP01]
base_branch: 001-krypt-app-locker-WP01
base_commit: 3be5b1c4b9a96067bf5878d3e8d5ddd04b73f01c
created_at: '2026-04-24T06:15:54.963007+00:00'
subtasks: [T006, T007, T008, T009, T010, T011]
test_status: required
test_file: tests/e2e/WP02-wp02-crypto-primitives.spec.js
agent: "claude"
reviewed_by: "Prasath Kumar K"
review_status: "approved"
---

# WP02 - Crypto primitives (KDF + HKDF + AES-GCM + X25519)

## Objective

Implement all cryptographic primitives Krypt needs, using **only first-party `javax.crypto` / AndroidX** (no BouncyCastle add-on, no third-party crypto library). Every primitive lives in `com.krypt.app.crypto` and is exercised by JVM unit tests that assert both correctness (known-answer tests where standardised) and performance (KDF cost >=250 ms).

Downstream WPs (WP03 approval encryption, WP04 HMAC-verified pairing reply, WP15 approval consumption) wire these primitives into the deep-link protocol.

## Context

- **Spec FR-008:** Guardian PIN verification MUST apply >=300,000 PBKDF2-HMAC-SHA256 iterations, costing >=250 ms.
- **Spec FR-009:** AES-256 encryption for approval payloads.
- **Plan Engineering Alignment:** "PBKDF2-HMAC-SHA256 (>=300,000 iterations) + HKDF + AES-256-GCM, all via javax.crypto / AndroidX first-party."
- **Research R4:** PBKDF2 chosen over scrypt/Argon2 to avoid BouncyCastle add-on.
- **Data-model note:** `K_pair` (32 bytes X25519 shared secret) is established at pairing time and persisted via `KPairStore`.

## Subtasks

### T006 - PBKDF2 wrapper with adaptive calibration

**Purpose.** Wrapper around `javax.crypto.SecretKeyFactory` for PBKDF2-HMAC-SHA256. Exposes `derive(pin, salt, iterations, outputBytes)` and `calibrateIterationsForDevice(targetMillis)` that binary-searches the iteration count that produces the requested cost on this device (called once at onboarding).

**Files to create:**
- `app/src/main/java/com/krypt/app/crypto/KdfProvider.kt`

**Implementation steps:**
1. `class KdfProvider @Inject constructor()` - no state.
2. `fun derive(pin: CharArray, salt: ByteArray, iterations: Int, outputBytes: Int): ByteArray` - uses `PBEKeySpec(pin, salt, iterations, outputBytes * 8)` then `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded`. Immediately calls `spec.clearPassword()` in a `finally` block.
3. `fun calibrateIterationsForDevice(targetMillis: Long = 300, floor: Int = 300_000): Int` - starting at `floor`, measure one derive call; if below target, double and retry; when over target, linearly adjust down by 10% until in [target, target*1.2] band. Return final iteration count.
4. Accept `CharArray` for PIN (not `String`) so caller can zero it after use; best-effort defence against heap-dump PIN exfiltration.

**Validation.**
- PBKDF2 KAT: iterations=4096, salt="salt", password="password", expect 32 bytes per RFC 6070 (use HMAC-SHA1 test vector to cross-check algorithm plumbing; SHA-256 vectors are less famous but test against OpenSSL-computed reference).
- `calibrateIterationsForDevice(300)` returns a value >=300,000 on CI hardware (GitHub Actions Linux runner: expect ~450,000-800,000).

### T007 - HKDF-SHA-256 wrapper

**Purpose.** Derive `K_req` from `K_pair + requestId + salt` per `contracts/approve.md`.

**Files to create:**
- `app/src/main/java/com/krypt/app/crypto/KeyDeriver.kt`

**Implementation steps:**
1. `object KeyDeriver` (singleton; no state).
2. `fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLength: Int): ByteArray` - two-step extract + expand per RFC 5869. Uses `javax.crypto.Mac.getInstance("HmacSHA256")`.
3. Guard: `require(outLength <= 255 * 32)` (HKDF-SHA-256 max output).
4. Zero the intermediate `prk` byte array on exit.

**Validation.**
- RFC 5869 Test Case 1 (SHA-256 basic): IKM 22 bytes, salt 13 bytes, info 10 bytes, L=42 -> PRK + OKM match RFC hex values exactly.

### T008 - AES-256-GCM cipher

**Purpose.** Sealed-box encryption for approval payloads. 12-byte random nonce + 16-byte auth tag per JCE convention.

**Files to create:**
- `app/src/main/java/com/krypt/app/crypto/AesGcmCipher.kt`

**Implementation steps:**
1. `object AesGcmCipher`.
2. `fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray` - `require(key.size == 32 && nonce.size == 12)`. Cipher `"AES/GCM/NoPadding"`, `SecretKeySpec(key, "AES")`, `GCMParameterSpec(128, nonce)`. If `aad != null`, `cipher.updateAAD(aad)`. Return `cipher.doFinal(plaintext)` (ciphertext includes the 16-byte tag suffix).
3. `fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray? = null): ByteArray` - mirror of encrypt. Throws `AEADBadTagException` on tamper.
4. Do NOT expose a convenience method that bundles nonce inside the ciphertext; callers handle that layout explicitly per contract.

**Validation.**
- `AesGcmCipherTest`:
  - Known-answer test from NIST GCM KAT (Test Case 3, 128-bit key extended to 256-bit): verify ciphertext + tag bytes match.
  - Round-trip: random 32-byte key, random 12-byte nonce, random 128-byte plaintext; decrypt reproduces plaintext.
  - Tamper test: flip any bit in ciphertext or tag -> `AEADBadTagException`.
  - AAD test: encrypt with AAD="abc"; decrypt with AAD="abd" -> `AEADBadTagException`.

### T009 - X25519 key agreement

**Purpose.** Ephemeral keypair generation + shared-secret derivation for pairing. 32-byte shared secret K_pair.

**Files to create:**
- `app/src/main/java/com/krypt/app/crypto/X25519KeyAgreement.kt`

**Implementation steps:**
1. `object X25519KeyAgreement`.
2. `fun generateEphemeralKeyPair(): KeyPair` - uses `KeyPairGenerator.getInstance("XDH")` with `NamedParameterSpec("X25519")`. Android supports this via the Conscrypt provider on API 31+. **API 29/30 caveat:** the XDH algorithm is not available in the default provider on API 29-30. Fallback: link AndroidX `security-crypto` 1.1-alpha which bundles a backport, OR use `java.security.interfaces.XECPublicKey` with `KeyFactory.getInstance("X25519", "AndroidOpenSSL")` where available. If the unambiguous fallback is to link Conscrypt directly, document it. If no first-party path exists on 29-30, raise an issue.
3. `fun derivePublicKey(privateKey: PrivateKey): ByteArray` - extracts the 32-byte raw public key from an `XECPublicKey` via `getU()` then little-endian-encodes.
4. `fun agree(myPrivate: PrivateKey, theirPublicRaw: ByteArray): ByteArray` - uses `KeyAgreement.getInstance("XDH").init(myPrivate).doPhase(theirPublicKey, true).generateSecret()` where `theirPublicKey` is rebuilt from raw bytes via `KeyFactory.getInstance("XDH").generatePublic(XECPublicKeySpec(params, BigInteger u))`.
5. Zero `privateKey` bytes where possible (Android's EC private keys do not reliably expose internal bytes; best-effort).

**Validation.**
- RFC 7748 Section 6.1 test vector: given Alice's private 32 bytes + Bob's public 32 bytes, shared secret must equal `c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552`.
- Round-trip: generate two keypairs; `agree(A.priv, B.pub) == agree(B.priv, A.pub)`.
- **Fallback plan** if API 29/30 XDH is missing: defer to WP04 for a decision on shipping Conscrypt-embedded vs. raising `minSdk` to 31. Document in the T009 PR body.

### T010 - SecureRandom source (injectable)

**Purpose.** Thin injectable wrapper around `SecureRandom` so tests can stub deterministic randomness while production uses real entropy.

**Files to create:**
- `app/src/main/java/com/krypt/app/crypto/SecureRandomSource.kt`

**Implementation steps:**
1. Interface:
   ```
   interface SecureRandomSource {
       fun nextBytes(size: Int): ByteArray
   }
   ```
2. Default impl (production):
   ```
   class RealSecureRandomSource @Inject constructor() : SecureRandomSource {
       private val rng = SecureRandom()
       override fun nextBytes(size: Int) = ByteArray(size).also(rng::nextBytes)
   }
   ```
3. Hilt module (defer the `@Module` + `@Provides` to `di/AppModule.kt` authored in WP12 or add a minimal module now) binding `SecureRandomSource` -> `RealSecureRandomSource`.
4. Test impl `FakeSecureRandomSource(seed: Long)` in `src/test/...` for determinism.

**Validation.**
- `nextBytes(32)` returns a 32-byte array. Two consecutive calls return different arrays (statistical).

### T011 - JVM unit tests for crypto

**Purpose.** Comprehensive coverage of all primitives. All tests run under `./gradlew :app:testDebugUnitTest`.

**Files to create:**
- `app/src/test/java/com/krypt/app/crypto/KdfProviderTest.kt`
- `app/src/test/java/com/krypt/app/crypto/KeyDeriverTest.kt`
- `app/src/test/java/com/krypt/app/crypto/AesGcmCipherTest.kt`
- `app/src/test/java/com/krypt/app/crypto/X25519Test.kt`

**Implementation steps:**
1. `KdfProviderTest` - two tests: `calibrationReachesTargetCost` (calibrate for 300 ms, assert measured cost is in `[250, 400]` ms); `deriveIsDeterministic` (same inputs -> same outputs). Time-sensitive test tolerates ~20% wiggle for CI variance.
2. `KeyDeriverTest` - RFC 5869 test case 1, 2, 3 (SHA-256 vectors).
3. `AesGcmCipherTest` - KAT (one NIST vector), round-trip random plaintext, tamper (ciphertext flip, tag flip, AAD mismatch).
4. `X25519Test` - RFC 7748 vector + round-trip agreement.

**Validation.**
- `./gradlew :app:testDebugUnitTest --tests "com.krypt.app.crypto.*"` passes within 5 seconds.
- Every test has an `@DisplayName` or clear Kotlin test-method name so failures are self-describing.

## Test Strategy

- **Unit (JVM):** T011 covers 100% of crypto class surface.
- **Integration:** NONE at this stage. Crypto primitives stand alone.
- **Performance:** T011 `KdfProviderTest.calibrationReachesTargetCost` implicitly benchmarks; captures FR-008 regression.

## Definition of Done

- [ ] All five `com.krypt.app.crypto.*` classes exist and compile.
- [ ] `./gradlew :app:testDebugUnitTest` passes with all 10-15 crypto tests green.
- [ ] KdfProvider passes RFC 6070 or equivalent KAT.
- [ ] KeyDeriver passes RFC 5869 test vectors.
- [ ] AesGcmCipher passes NIST GCM KAT + tamper rejection test.
- [ ] X25519KeyAgreement passes RFC 7748 vector OR has a documented fallback plan for API 29/30.
- [ ] No third-party crypto dependency added to `libs.versions.toml`.

## Risks + Edge cases

- **X25519 availability on API 29/30.** Primary risk. The `XDH` KeyPairGenerator is reliably present only on API 31+. Three fallback options, in order of preference:
  1. Raise minSdk to 31 (spec allows flexibility; plan defaults to 29 but not immutably).
  2. Vendor Conscrypt as an AAR - technically first-party (Google) and often already transitive via AndroidX, but adds ~700 KB.
  3. Implement X25519 manually using `java.math.BigInteger` modular arithmetic - slow, potentially side-channel-leaky, not recommended.
  Document the decision + rationale in the WP PR.
- **PIN CharArray vs String.** Compose `TextField` emits `String` state. WP14 (GuardianActivity PIN UI) must call `pin.toCharArray()` ONCE, pass it here, and blank the String reference. Heap dumps are a theoretical concern only; document the limitation.
- **SecureRandom seeding.** Android's `SecureRandom` uses `/dev/urandom`. On API 24+ this is robust; on older Androids there were historical seeding bugs. We target API 29+ so safe.
- **Timing side channels.** PBKDF2's iteration count is a universal cost; there is no "correct vs incorrect PIN" branch in the KDF itself. Equality check downstream MUST use `MessageDigest.isEqual` (constant-time), not `contentEquals`.

## Reviewer Guidance

- Grep for `contentEquals` in crypto code; if found on any ByteArray comparing secrets, flag for replacement with `MessageDigest.isEqual`.
- Confirm no `Random()` (java.util) anywhere; only `SecureRandom` via `SecureRandomSource`.
- Run `./gradlew :app:testDebugUnitTest` and paste the timing for `calibrationReachesTargetCost` in the review.
- Ask the author which X25519 path they chose (Conscrypt / minSdk-31 / other) and why.

## Next command

```
polaris implement WP02 --base WP01
```

## Activity Log

<<<<<<< HEAD
- 2026-04-24T06:27:21Z – claude – lane=doing – Crypto implementation begun
- 2026-04-24T06:27:29Z – claude – lane=testing – 34 JVM unit tests authored
- 2026-04-24T06:27:38Z – claude – lane=for_review – WP02 ready for review. Commit: 6c2aebf feat(WP02) on branch 001-krypt-app-locker-WP02 (based on WP01). 10 source files: KdfProvider (PBKDF2-HMAC-SHA256 with MIN_ITERATIONS=300k + linear-scale calibration), KeyDeriver (HKDF-SHA-256 RFC 5869), AesGcmCipher (AES-256-GCM with explicit nonce), X25519KeyAgreement (RFC 7748, API 29/30 XDH caveat documented), SecureRandomSource (interface + real + Hilt bindings), CryptoModule. 34 JVM tests across 4 test files including RFC 5869 and RFC 7748 KATs. Reviewer runs ./gradlew :app:testDebugUnitTest; AES-GCM tamper/AAD tests, X25519 tests have assumeNoException to skip if JCE provider lacks XDH.
=======
- 2026-04-24T06:15:58Z – claude – shell_pid=35948 – lane=doing – Assigned agent via workflow command
>>>>>>> 001-krypt-app-locker-WP02
- 2026-04-24T06:40:54Z – claude – lane=done – Merged to main as 7a4bde9 (merge commit; feat commit 6c2aebf)
