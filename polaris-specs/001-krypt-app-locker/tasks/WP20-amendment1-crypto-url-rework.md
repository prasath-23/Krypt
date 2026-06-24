---
work_package_id: WP20
lane: "done"
review_status: approved
reviewed_by: Prasath Kumar K
dependencies: [WP19]
base_branch: main
created_at: '2026-04-24T11:10:00+00:00'
subtasks: [T105, T106, T107, T108, T109]
test_status: required
test_file: tests/e2e/WP20-amendment1-crypto-url-rework.spec.js
amendment: 1
domain: backend-logic
---

# WP20 - Amendment 1: Request / Approve URL rework (PBKDF2-only, no X25519)

## Objective

Rework `UnlockRequestBuilder/Parser`, `ApprovalLinkBuilder/Parser`, `ApprovalPayloadCodec`, and `ApprovalConsumer` so the cryptographic root is `MasterKey` (PBKDF2-derived), not `K_pair` (X25519-derived). Introduce the `pinProof` field in the request URL. Tighten `OutstandingRequest.ttlSeconds` default to 300. Satisfies FR-007 (amended), FR-009 (amended), FR-019, and FR-020.

## Context

- **Spec Amendment 1:** Section "What changed" bullets 3, 4, 5; FR-007, FR-009, FR-019, FR-020.
- **Depends on:** `MasterKeyStore` (WP19), `HmacProvider` (WP19), `KdfProvider + KeyDeriver + AesGcmCipher` (WP02), `OutstandingRequestRepository` (WP06).

## Subtasks

### T105 - Update request URL shape (add pinProof, default ttl=300)

**Files to modify:**
- `app/src/main/java/com/krypt/app/deeplink/UnlockRequest.kt` (add `pinProof: ByteArray` field)
- `app/src/main/java/com/krypt/app/deeplink/UnlockRequestBuilder.kt`
- `app/src/main/java/com/krypt/app/deeplink/UnlockRequestParser.kt`
- `polaris-specs/001-krypt-app-locker/contracts/request.md` (add Amendment 1 note at top)

**Implementation:**
1. `UnlockRequest` data class gains `val pinProof: ByteArray` (32 bytes). Update `equals/hashCode/toString` (ByteArray).
2. `UnlockRequestBuilder.build(targetPackage, masterKeyStore)` now: loads `salt + pinProof` from `MasterKeyStore` (NOT per-request - uses the setup-time salt and pinProof), generates `requestId = UUID.randomUUID()`, writes URL with `salt=<b64url(setup_salt)>&pinProof=<b64url(pinProof)>&ttl=300`. `OutstandingRequest.requestSalt` still stores a per-request random 16-byte nonce for use as HKDF info material in the approval layer.
3. `UnlockRequestParser.parse` reads `pinProof` (required, 32 bytes). New `RequestParseError.BadPinProof` for wrong length.
4. The URL's `salt` is now the setup-time salt (16 bytes); the per-request nonce moves into the approval URL `data` blob payload (the existing CBOR-inside-AES-GCM field), not into the request URL.

### T106 - Rework ApprovalLinkBuilder / ApprovalConsumer key derivation

**Files to modify:**
- `app/src/main/java/com/krypt/app/deeplink/ApprovalLinkBuilder.kt`
- `app/src/main/java/com/krypt/app/deeplink/ApprovalConsumer.kt`
- `app/src/main/java/com/krypt/app/deeplink/ApprovalPayload.kt` (no shape change - still 5 CBOR fields)
- `polaris-specs/001-krypt-app-locker/contracts/approve.md` (add Amendment 1 note)

**Implementation:**
1. `ApprovalLinkBuilder.build(masterKey, request, grantDurationMinutes = 15)`:
   - `nonceForHkdf = rng.nextBytes(16)` (NEW - per-request bind).
   - `kReq = KeyDeriver.hkdfSha256(ikm = masterKey, salt = "krypt/v1/approve".toByteArray(), info = request.requestId.toString().toByteArray() + nonceForHkdf, outLength = 32)`.
   - Payload includes `nonceForHkdf` (binary) alongside existing fields. OR: prepend it to the AES-GCM `data` blob (so the Subject can read it BEFORE decryption) - PREFER this option. So the `data` blob layout becomes: `nonceForHkdf(16) || aesNonce(12) || ciphertext || tag(16)`.
   - Still returns a `Uri`.
2. `ApprovalConsumer.consume(uri)` (now `@Inject` with `MasterKeyStore`):
   - Loads `masterKey = store.loadMasterKey() ?: return NotConfigured`.
   - Parses `data` blob: `nonceForHkdf = data.sliceArray(0..15)`; `aesNonce = data.sliceArray(16..27)`; `ctAndTag = data.sliceArray(28..end)`.
   - Derives `kReq` identically to builder.
   - Decrypts, CBOR-parses, continues existing pipeline (check consumed, atomic flip, insert grant).

### T107 - Remove X25519 from live path

**Files to modify:**
- `app/src/main/java/com/krypt/app/security/KPairStore.kt` - deprecate: annotate `@Deprecated("Amendment 1: X25519 pairing removed. Use MasterKeyStore.", level = DeprecationLevel.WARNING)`. Do NOT delete the file - keep implementation so `X25519KeyAgreement` still compiles.
- `app/src/main/java/com/krypt/app/di/CoreModule.kt` - the `KPairStore` binding stays but no callers outside legacy code.

Legacy WP04 pairing code (`SubjectPairingBuilder`, `GuardianPairingBuilder`, `krypt://pair`/`paired` parsers) remains untouched but is unreachable from UI after WP19's `MainRoute` changes.

### T108 - Update OutstandingRequest TTL default + single-use enforcement

**Files to modify:**
- `app/src/main/java/com/krypt/app/data/OutstandingRequestDao.kt` - confirm atomic method `UPDATE outstanding_requests SET consumed=1 WHERE requestId=? AND consumed=0` exists (added in WP06; verify). If not, add.
- `app/src/main/java/com/krypt/app/deeplink/UnlockRequestBuilder.kt` - default `ttlSeconds = 300L` (was 1800L).

### T109 - Unit + property tests

**Files to create:**
- `app/src/test/java/com/krypt/app/deeplink/Amendment1RequestRoundTripTest.kt` - 50 random pin-proofs, build+parse round-trip equality.
- `app/src/test/java/com/krypt/app/deeplink/Amendment1ApprovalRoundTripTest.kt` - for a known `masterKey`, builder -> consumer yields `Ok` with matching fields; tamper in any byte of `data` blob -> `CipherDecryptFailed`.
- `app/src/test/java/com/krypt/app/deeplink/Amendment1ReplayDefenseTest.kt` - consume once -> Ok; consume again -> `UnmatchedRequest`; two parallel consumes on `Dispatchers.Default` -> exactly one Ok.

## Definition of Done

- [ ] `UnlockRequest.pinProof` round-trips through URL encode+parse.
- [ ] `ApprovalConsumer` decrypts approvals built by `ApprovalLinkBuilder` when both sides share the same `masterKey`.
- [ ] Approval built with a different `masterKey` fails `CipherDecryptFailed`.
- [ ] Atomic `consumed` flip enforces single-use (parallel test).
- [ ] Default `OutstandingRequest.ttlSeconds = 300`; requests with `iat + 300 < now` rejected with `Expired`.
- [ ] Contract docs updated with Amendment 1 callouts.
- [ ] No third-party dependency added.

## Risks and Edge cases

- **Clock skew.** Tighter TTL (300s) leaves less room for device-clock drift. Keep the 60-second tolerance constant from WP03 so legitimate drift doesn't trip users.
- **nonceForHkdf in plaintext.** The 16-byte prefix is not secret - it's input material to HKDF. An attacker seeing it learns nothing without `masterKey`. Test that truncating/tampering those 16 bytes changes the derived `kReq` so AES-GCM auth fails.
- **Backward-compat with shipped WP03 URLs.** This amendment deliberately breaks the URL format. Since the full v1 is sideload-only and Amendment 1 is pre-GA, no migration handler is required; an old URL simply fails parsing with `MissingParam("pinProof")`.

## Activity Log

- 2026-04-24T18:27:38Z -- unknown -- lane=for_review -- WP20 Amendment 1 URL rework ready. Commit fb066a5 on 001-krypt-app-locker-WP20 (stacked on WP19). Changes: UnlockRequest gains pinProof(32B) + SALT_BYTES=setup salt; default TTL 300s; ApprovalLinkBuilder takes masterKey; data blob = nonceForHkdf(16) || aesNonce(12) || ct || tag; ApprovalConsumer uses MasterKeyStore; KPairStore @Deprecated (legacy pairing still compiles); GuardianRequestScreen + GuardianApprovalConsumeScreen deleted. Tests: 33 JVM tests across 3 Amendment1* files. No third-party dep. Reviewer runs ./gradlew :app:testDebugUnitTest.
