---
work_package_id: WP04
lane: "done"
dependencies: [WP01, WP02]
base_branch: 001-krypt-app-locker-WP02
base_commit: 6c2aebf109594807e377a987e02dacd2dffb7ff9
created_at: '2026-04-24T07:05:25.121078+00:00'
subtasks: [T017, T018, T019, T020, T021]
test_status: required
test_file: tests/e2e/WP04-wp04-deeplink-pair-paired.spec.js
agent: "claude"
reviewed_by: "Prasath Kumar K"
review_status: "approved"
---

# WP04 - Deep-link scheme: `krypt://pair` + `krypt://paired`

## Objective

Implement the pairing-time deep-link pair: Subject emits `krypt://pair` on first run; Guardian replies with HMAC-SHA-256-authenticated `krypt://paired`. Both sides contribute an X25519 ephemeral public key; the shared secret `K_pair` is derived and persisted as the long-term symmetric key for the Subject-Guardian channel.

Contracts `contracts/pair.md` and `contracts/paired.md` are authoritative.

## Context

- **Contracts:** `polaris-specs/001-krypt-app-locker/contracts/pair.md` + `.../contracts/paired.md`.
- **Data model:** `GuardianPairing` entity; `K_pair` persisted via `KPairStore` interface (declared here, backed by EncryptedSharedPreferences in WP06).
- **Crypto deps (WP02):** `X25519KeyAgreement`, `SecureRandomSource`. HMAC-SHA-256 added here (not in WP02) since it's a small enough primitive.

## Subtasks

### T017 - PairRequestBuilder + PairRequestParser

**Purpose.** Build `krypt://pair?...` on Subject, parse on Guardian.

**Files to create:**
- `app/src/main/java/com/krypt/app/deeplink/PairRequest.kt` (data class + errors)
- `app/src/main/java/com/krypt/app/deeplink/PairRequestBuilder.kt`
- `app/src/main/java/com/krypt/app/deeplink/PairRequestParser.kt`

**Implementation steps:**
1. `PairRequest(subjectId: UUID, subjectDisplayName: String, subjectEphPub: ByteArray, issuedAt: Long, ttlSeconds: Long)`. Manual `equals`/`hashCode` (ByteArray).
2. `sealed interface PairParseError` with `BadScheme, WrongVersion, MissingParam, BadBase64, BadUuid, Expired`.
3. `PairRequestBuilder.build(subjectId: UUID, subjectDisplayName: String, ephPrivate: PrivateKey): Uri`:
   - Derives `ephPub = X25519.derivePublicKey(ephPrivate)` (32 bytes).
   - Packs `v, sub, subName (URL-encoded), ephPub (b64url), iat, ttl=900` per contract.
   - Does NOT persist ephPrivate (caller's responsibility; typically held in `PairingSessionViewModel` memory).
4. `PairRequestParser.parse(uri: Uri, now: Long): Result<PairRequest, PairParseError>`.

**Validation.**
- Round-trip: build -> parse -> fields equal.
- Each error branch has a targeted test.

### T018 - PairedReplyBuilder (Guardian side)

**Purpose.** Compute X25519 shared secret + HMAC-authenticated reply URL per `contracts/paired.md`.

**Files to create:**
- `app/src/main/java/com/krypt/app/deeplink/PairedReply.kt`
- `app/src/main/java/com/krypt/app/deeplink/PairedReplyBuilder.kt`
- `app/src/main/java/com/krypt/app/crypto/HmacSha256.kt` (small helper)

**Implementation steps:**
1. `HmacSha256`:
   - `object HmacSha256`.
   - `fun mac(key: ByteArray, message: ByteArray): ByteArray` - standard `Mac.getInstance("HmacSHA256")`.
2. `PairedReply(subjectId, guardianDisplayName, guardianPubSalt, guardianEphPub, kdfIterations, issuedAt, ttlSeconds, mac)`.
3. `PairedReplyBuilder.buildWithFreshAgreement(incomingRequest: PairRequest, guardianDisplayName: String, guardianEphPrivate: PrivateKey, guardianPubSalt: ByteArray, guardianKdfIterations: Int): Pair<Uri, ByteArray /*K_pair*/>`:
   - `kPair = X25519KeyAgreement.agree(guardianEphPrivate, incomingRequest.subjectEphPub)`.
   - Canonical MAC input: `pubSalt || ephPub || kdfIter(8 bytes big-endian) || iat(8 bytes BE) || ttl(8 bytes BE) || subjectId(16 bytes)`. Implement the BE byte conversion carefully.
   - `mac = HmacSha256.mac(kPair, canonical)`.
   - Uri assembled per contract.
   - Returns `(uri, kPair)` so the Guardian's caller can persist `kPair` immediately.
4. `PairedReplyBuilder.buildWithExistingKPair(subjectId: UUID, kPair: ByteArray, guardianDisplayName: String, newGuardianPubSalt: ByteArray, kdfIterations: Int, prevGuardianEphPub: ByteArray): Uri`:
   - For PIN rotation: reuse existing K_pair, re-sign with fresh salt.
   - `ephPub` in this case is the Guardian's LAST-USED ephPub (not regenerated).

**Validation.**
- Build round-trip against T019 parser: builder output parses to matching fields.
- MAC over a crafted input matches a hand-computed HMAC-SHA-256 reference value.

### T019 - PairedReplyVerifier (Subject side)

**Purpose.** Parse + verify HMAC on Subject; derive `K_pair`; return tuple.

**Files to create:**
- `app/src/main/java/com/krypt/app/deeplink/PairedReplyVerifier.kt`
- `app/src/main/java/com/krypt/app/deeplink/PairedParseError.kt`

**Implementation steps:**
1. `sealed interface PairedParseError` with `BadScheme, WrongVersion, MissingParam(name), BadBase64, WrongSubjectId, Expired, BadMac`.
2. `PairedReplyVerifier`:
   - `@Inject constructor(private val x25519: X25519KeyAgreement)`.
   - `fun parseAndVerify(uri: Uri, now: Long, myEphPrivate: PrivateKey, myExpectedSubjectId: UUID): Result<Pair<PairedReply, ByteArray>, PairedParseError>`.
   - Parse shape, validate `sub == myExpectedSubjectId`, decode `ephPub`.
   - Derive `kPair = x25519.agree(myEphPrivate, guardianEphPub)`.
   - Recompute canonical MAC input exactly as builder (T018) did.
   - `expectedMac = HmacSha256.mac(kPair, canonical)`.
   - Compare using `MessageDigest.isEqual(expectedMac, receivedMac)` (constant-time).
   - On success return `(PairedReply, kPair)`.

**Validation.**
- Happy path round-trip with T018 output.
- Flip any bit in `mac` parameter -> `BadMac`.
- Flip any bit in `pubSalt` parameter -> `BadMac` (canonical input changes).
- Wrong `sub` -> `WrongSubjectId`.
- Expired `iat + ttl` -> `Expired`.

### T020 - KPairStore interface + fake impl

**Purpose.** Declare the interface for the persisted K_pair. Real EncryptedSharedPreferences-backed impl is in WP06.

**Files to create:**
- `app/src/main/java/com/krypt/app/crypto/KPairStore.kt`
- `app/src/test/java/com/krypt/app/crypto/FakeKPairStore.kt`

**Implementation steps:**
1. Interface:
   ```
   interface KPairStore {
       suspend fun save(kPair: ByteArray)
       suspend fun load(): ByteArray?
       suspend fun clear()
       fun isPaired(): Boolean   // fast synchronous check for UI gates
   }
   ```
2. `FakeKPairStore` - in-memory `@Volatile var`; for tests only.

**Validation.**
- Fake-impl round-trip: save -> load -> equals.

### T021 - JVM pairing tests

**Purpose.** Round-trip + tamper tests.

**Files to create:**
- `app/src/test/java/com/krypt/app/deeplink/PairRoundTripTest.kt`
- `app/src/test/java/com/krypt/app/deeplink/PairedReplyMacTamperTest.kt`

**Implementation steps:**
1. `PairRoundTripTest`:
   - Generate Subject ephemeral keypair, build pair URL, parse on fake "Guardian", generate Guardian ephemeral keypair, build paired reply, parse+verify on "Subject", assert both devices derive the same `K_pair`.
   - Derive `K_pair` length is exactly 32 bytes.
2. `PairedReplyMacTamperTest`:
   - For each MAC-covered field (`pubSalt`, `ephPub`, `kdfIter`, `iat`, `ttl`, `sub`, plus the `mac` field itself), flip a random bit and assert the verifier returns `BadMac`.
   - Paramaterised test or a loop; ensure ~30 total cases.

**Validation.**
- All tests green under `./gradlew :app:testDebugUnitTest --tests "com.krypt.app.deeplink.Pair*"`.

## Test Strategy

- **Unit (JVM):** T021 covers round-trip and tamper.
- **Integration:** Deferred to WP13 (pairing UI) and WP17 (E2E).
- **Security:** Tamper test is the primary security property.

## Definition of Done

- [ ] All files compile; tests pass.
- [ ] Round-trip: both sides derive identical `K_pair` from their ephemerals.
- [ ] 30+ tamper cases all detected as `BadMac`.
- [ ] `PairedReplyVerifier` uses `MessageDigest.isEqual` (constant-time) for MAC comparison.
- [ ] `KPairStore` interface only - real impl deferred to WP06.

## Risks + Edge cases

- **Canonical MAC input order.** Getting the byte concatenation order wrong produces an undetectable vulnerability (both sides agree on a wrong order, no tests fail). Mitigation: write the canonical format down in T018 code comments AND reference `contracts/paired.md` line + field numbers.
- **Integer BE encoding across Android APIs.** `Long.toBigEndianBytes()` is not in stdlib; write a local helper `fun Long.toBytesBe(): ByteArray` rather than relying on `ByteBuffer.putLong` conventions. Unit-test the helper.
- **X25519 fallback (from WP02 risk).** Inherits. If WP02 chose Conscrypt / minSdk bump, WP04 uses the same path.
- **Replay resistance.** A captured `krypt://paired` URL could in principle be re-delivered to the same Subject. Because Subject verifies `sub == myExpectedSubjectId`, and once paired, a later `krypt://paired` with the same `sub` but different `pubSalt` is a PIN-rotation event - we accept it. A captured OLD `krypt://paired` that's been superseded is still valid from a cryptographic standpoint (Subject can overwrite to the older salt). Mitigation: Subject accepts `paired` only when `iat > lastPairedAt` (monotonic). Add as a constraint in T019 parser's expiry check.
- **`subName` URL-encoding.** Multi-byte characters (e.g., "Alice's 📱 Pixel") must round-trip through `Uri.Builder.appendQueryParameter` and `Uri.getQueryParameter`. Validate with an emoji-name test case.

## Reviewer Guidance

- Confirm canonical MAC input format in T018 matches contracts/paired.md byte-for-byte.
- Confirm `MessageDigest.isEqual` used in T019; flag `contentEquals` or `==` as vulnerabilities.
- Verify T021 includes at least one "emoji displayName" case to catch URL-encoding bugs.
- Ask the author to paste the `K_pair` hex from two successful round-trips (different ephemerals) in the PR body to demonstrate they differ (non-determinism = correct).

## Next command

```
polaris implement WP04 --base WP02
```

## Activity Log

- 2026-04-24T07:10:21Z – claude – lane=doing – pairing impl
- 2026-04-24T07:10:29Z – claude – lane=testing – tests authored
- 2026-04-24T07:10:36Z – claude – lane=for_review – WP04 ready
- 2026-04-24T07:12:07Z – claude – lane=done – Merged
