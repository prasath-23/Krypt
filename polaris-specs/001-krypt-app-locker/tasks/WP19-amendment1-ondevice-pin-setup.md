---
work_package_id: WP19
lane: "doing"
dependencies: [WP01, WP02, WP05, WP06, WP11, WP12]
base_branch: main
base_commit: adf0d7cfc4aee42c28a3e7bf63da4c117ac37329
created_at: '2026-04-24T12:17:04.937033+00:00'
subtasks: [T100, T101, T102, T103, T104]
test_status: required
test_file: tests/e2e/WP19-amendment1-ondevice-pin-setup.spec.js
amendment: 1
domain: backend-logic
shell_pid: "36600"
---

# WP19 - Amendment 1: On-device PIN setup flow (Guardian sets PIN on Subject device)

## Objective

Implement the new first-time setup flow where the Guardian physically holds the Subject device, types the PIN once, and the Subject device derives and persists `MasterKey + salt + pinProof`. Replaces the `krypt://pair` / `krypt://paired` round-trip (WP04) and the pairing UI (WP13) in the live path. Satisfies FR-017 (PIN not persisted) and amended US-1.

## Context

- **Spec Amendment 1:** Section "What changed" bullet 1; FR-017, FR-018.
- **Crypto dependency:** `KdfProvider` (PBKDF2, WP02), `SecureRandomSource` (WP02). HMAC-SHA-256 is a thin wrapper around `javax.crypto.Mac`.
- **Storage:** `androidx.security:security-crypto` `EncryptedSharedPreferences` with `MasterKey.Builder(...).setKeyScheme(AES256_GCM).build()`.

## Subtasks

### T100 - MasterKeyStore (replaces KPairStore in live path)

**Files to create:**
- `app/src/main/java/com/krypt/app/security/MasterKeyStore.kt`

**Implementation:**
1. `interface MasterKeyStore { suspend fun isConfigured(): Boolean; suspend fun save(salt: ByteArray, masterKey: ByteArray, pinProof: ByteArray); suspend fun loadMasterKey(): ByteArray?; suspend fun loadSalt(): ByteArray?; suspend fun loadPinProof(): ByteArray?; suspend fun clear() }`.
2. `class EncryptedPrefsMasterKeyStore @Inject constructor(@ApplicationContext ctx: Context)` using `EncryptedSharedPreferences.create("krypt_master", MasterKey.Builder(ctx).setKeyScheme(AES256_GCM).build(), ctx, AES256_SIV, AES256_GCM)`. Stores base64 strings under keys `salt|masterKey|pinProof`.
3. Hilt binding in `di/SecurityModule.kt`.

### T101 - PinSetupScreen (Compose)

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/setup/PinSetupScreen.kt`
- `app/src/main/java/com/krypt/app/ui/setup/PinSetupViewModel.kt`

**Implementation:**
1. 4-6 digit numeric PIN field (Compose `BasicTextField` with `KeyboardType.NumberPassword`). Mask shown as dots. Confirm field; match required.
2. "Set Guardian PIN" primary button is disabled until both fields have >=4 digits and match.
3. `@HiltViewModel class PinSetupViewModel @Inject constructor(kdf: KdfProvider, rng: SecureRandomSource, hmac: HmacProvider, store: MasterKeyStore)` exposes a `suspend fun save(pin: CharArray): Result<Unit, PinSetupError>` that:
   - Generates `salt = rng.nextBytes(16)`.
   - `iterations = kdf.calibrateIterationsForDevice(targetMillis=300, floor=300_000)` (cached after first run in DataStore so the calibration cost isn't paid on every setup).
   - `masterKey = kdf.derive(pin, salt, iterations, 32)`.
   - `pinProof = hmac.sha256(masterKey, "krypt/v1/pin-proof".toByteArray())`.
   - `store.save(salt, masterKey, pinProof)`.
   - Zero `pin` CharArray (fill with ` `).
4. Screen shows spinner during the ~300 ms KDF call.

### T102 - HmacProvider (new thin wrapper)

**Files to create:**
- `app/src/main/java/com/krypt/app/crypto/HmacProvider.kt`

**Implementation:**
1. `object HmacProvider` (or Hilt-injected class for consistency).
2. `fun sha256(key: ByteArray, data: ByteArray): ByteArray` using `javax.crypto.Mac.getInstance("HmacSHA256")`. Output is 32 bytes.

### T103 - Wire PinSetupScreen into OnboardingScreen

**Files to modify:**
- `app/src/main/java/com/krypt/app/ui/onboarding/OnboardingScreen.kt` (or equivalent from WP12)
- `app/src/main/java/com/krypt/app/ui/main/MainActivity.kt` (`AppScreen` enum / `MainRoute` navigation)

**Implementation:**
1. After the three permission-grant steps (Accessibility, Overlay, Device-Admin) complete and before marking onboarding complete, route to `PinSetupScreen`.
2. On `MasterKeyStore.isConfigured() == true`, mark onboarding complete and return to Home.
3. If a returning user opens the app and `!isConfigured()` but other onboarding is complete, force-route back to `PinSetupScreen` (e.g. post-data-clear).
4. Remove the old `PairingRoleChooserScreen` -> `SubjectPairScreen` path from the live navigation. Keep the files in the tree (archived under `ui/pairing/legacy/` or deleted with a one-line comment in `MainRoute` referencing Amendment 1).

### T104 - Unit tests

**Files to create:**
- `app/src/test/java/com/krypt/app/security/MasterKeyStoreTest.kt` (JVM; Robolectric for `EncryptedSharedPreferences` OR a fake impl tested for contract).
- `app/src/test/java/com/krypt/app/ui/setup/PinSetupViewModelTest.kt` (MockK; verify: zeroing of pin CharArray, store.save called with 16-byte salt + 32-byte masterKey + 32-byte pinProof).
- `app/src/test/java/com/krypt/app/crypto/HmacProviderTest.kt` (RFC 4231 HMAC-SHA-256 KAT: key="Jefe", data="what do ya want for nothing?" -> `5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843`).

## Definition of Done

- [ ] `EncryptedPrefsMasterKeyStore` persists and retrieves `{salt, masterKey, pinProof}` round-trip.
- [ ] `PinSetupScreen` routes correctly after permission grants; Save button disabled when PINs differ.
- [ ] After Save, `MasterKeyStore.isConfigured()` returns true and the typed `CharArray` has been zeroed (test asserts).
- [ ] HMAC-SHA-256 passes RFC 4231 KAT.
- [ ] No third-party dependency added.
- [ ] Legacy pairing screens no longer reachable via user navigation from Home.

## Risks and Edge cases

- **EncryptedSharedPreferences setup on API 29/30.** The 1.1.0-alpha06 library works on API 29+; confirm `MasterKey.Builder` doesn't hit `IllegalStateException` on older AOSP builds without StrongBox. Fallback: `.setRequestStrongBoxBacked(false)` explicitly.
- **PIN re-setup** (if user clears app data or Guardian wants to change PIN): the amendment spec says "physical reset session". Clearing `MasterKeyStore` regenerates a new `salt` and new `MasterKey`, which invalidates any in-flight approval URLs because the Guardian's recomputed `pinProof` will no longer match. This is the intended behaviour.
- **PIN strength.** 4-digit numeric PIN = 10,000 entries. The >=300k PBKDF2 iterations means brute force of a captured URL costs ~10,000 * 0.25 s = 42 minutes on equivalent hardware. Acceptable for v1 family-safety threat model; longer alphanumeric PINs can be added in a future amendment.
- **Onboarding resume.** If the user backgrounds during PinSetup, the `CharArray` MUST still be cleared via a `DisposableEffect { onDispose { pin.fill(' ') } }`.
