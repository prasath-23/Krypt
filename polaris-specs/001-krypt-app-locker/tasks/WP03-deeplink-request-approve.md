---
work_package_id: WP03
lane: "done"
dependencies: [WP01, WP02]
base_branch: 001-krypt-app-locker-WP02
base_commit: 6c2aebf109594807e377a987e02dacd2dffb7ff9
created_at: '2026-04-24T06:41:55.828119+00:00'
subtasks: [T012, T013, T014, T015, T016]
test_status: required
test_file: tests/e2e/WP03-wp03-deeplink-request-approve.spec.js
agent: "claude"
reviewed_by: "Prasath Kumar K"
review_status: "approved"
---

# WP03 - Deep-link scheme: `krypt://request` + `krypt://approve`

## Objective

Implement the two hot-path deep-link URIs that carry unlock requests (Subject -> Guardian) and unlock approvals (Guardian -> Subject) per the `contracts/request.md` and `contracts/approve.md` contract files. Builders are pure-Kotlin and work without an Android `Context`; parsers take `android.net.Uri`. AES-256-GCM encryption for approval payloads uses WP02 crypto primitives.

## Context

- **Contracts (authoritative):** `polaris-specs/001-krypt-app-locker/contracts/request.md`, `.../contracts/approve.md`.
- **Data model:** `data-model.md` entities `OutstandingRequest`, `UnlockGrant`.
- **Spec FR-006, FR-007, FR-009, FR-015.**
- **Crypto dependency:** WP02's `KeyDeriver` (HKDF) + `AesGcmCipher` + `SecureRandomSource`.

## Subtasks

### T012 - DeepLinkScheme constants + Base64 helpers

**Purpose.** Centralised constants so the scheme/authority strings live in exactly one place. URL-safe Base64 helpers that match the contract's encoding.

**Files to create:**
- `app/src/main/java/com/krypt/app/deeplink/DeepLinkScheme.kt`
- `app/src/main/java/com/krypt/app/deeplink/Base64Url.kt`

**Implementation steps:**
1. `object DeepLinkScheme { const val SCHEME = "krypt"; const val AUTHORITY_PAIR = "pair"; const val AUTHORITY_PAIRED = "paired"; const val AUTHORITY_REQUEST = "request"; const val AUTHORITY_APPROVE = "approve"; const val PROTOCOL_VERSION = "1" }`.
2. `object Base64Url` - `fun encode(b: ByteArray): String` using `Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)`. `fun decode(s: String): ByteArray` using `Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING)`. Both throw `IllegalArgumentException` on malformed input (callers translate to contract `BadBase64`).
3. Extension `fun Uri.Builder.krypt(authority: String): Uri.Builder = scheme(SCHEME).authority(authority).appendQueryParameter("v", PROTOCOL_VERSION)`.

**Validation.**
- Encode 32 random bytes -> decode round-trip produces identical bytes.
- Encode empty array -> empty string -> decode -> empty array.

### T013 - UnlockRequestBuilder + UnlockRequestParser

**Purpose.** `krypt://request?...` URL generation on Subject side + parse + shape-validation on Guardian side, per `contracts/request.md`.

**Files to create:**
- `app/src/main/java/com/krypt/app/deeplink/UnlockRequest.kt` (data class + errors)
- `app/src/main/java/com/krypt/app/deeplink/UnlockRequestBuilder.kt`
- `app/src/main/java/com/krypt/app/deeplink/UnlockRequestParser.kt`

**Implementation steps:**
1. `UnlockRequest.kt`:
   ```
   data class UnlockRequest(
       val requestId: UUID, val targetPackage: String, val salt: ByteArray,
       val issuedAt: Long, val ttlSeconds: Long
   ) { override fun equals(other: Any?)/hashCode()/toString() - manual (ByteArray) }

   sealed interface RequestParseError {
       object BadScheme : RequestParseError
       object WrongVersion : RequestParseError
       data class MissingParam(val name: String) : RequestParseError
       object BadBase64 : RequestParseError
       object BadUuid : RequestParseError
       object BadPackageName : RequestParseError
       object Expired : RequestParseError
   }
   ```
2. `UnlockRequestBuilder`:
   - `@Inject constructor(private val rng: SecureRandomSource, private val clock: Clock)`.
   - `fun build(targetPackage: String, requestId: UUID = UUID.randomUUID(), ttlSeconds: Long = 1800L): Pair<Uri, UnlockRequest>`.
   - Salt = `rng.nextBytes(16)`.
   - Returns the Uri + the domain object so the caller can persist to `OutstandingRequestDao` in the same transaction (see WP06 repository).
3. `UnlockRequestParser`:
   - `fun parse(uri: Uri, now: Long): Result<UnlockRequest, RequestParseError>` (use `kotlin.Result` or a local sealed `Outcome<T, E>` - caller preference; pick one and apply consistently across WP03/WP04).
   - Validates: scheme, authority, `v=1`, UUID format, regex for package name per contract, Base64 decode length == 16, TTL not expired.

**Validation.**
- `UnlockRequestBuilder.build("com.whatsapp")` produces a URL matching the contract's grammar exactly.
- Parser rejects each negative case with the right sealed error.

### T014 - ApprovalLinkBuilder + ApprovalPayloadCodec (CBOR)

**Purpose.** `krypt://approve?...` URL generation on Guardian side, including CBOR-encoded payload + AES-256-GCM encryption under `K_req = HKDF(K_pair, "krypt/v1/approve", req || salt)`.

**Files to create:**
- `app/src/main/java/com/krypt/app/deeplink/ApprovalPayload.kt` (domain data class)
- `app/src/main/java/com/krypt/app/deeplink/ApprovalPayloadCodec.kt` (minimal hand-rolled CBOR encode + decode)
- `app/src/main/java/com/krypt/app/deeplink/ApprovalLinkBuilder.kt`
- `app/src/main/java/com/krypt/app/deeplink/ApprovalLinkParser.kt`

**Implementation steps:**
1. `ApprovalPayload` data class mirroring the CBOR map in `contracts/approve.md`: `v, req, app, durMin, iat`.
2. `ApprovalPayloadCodec`:
   - Encoder writes the fixed 5-field CBOR map using numeric keys 1..5 and explicit type markers. Output ~50-80 bytes.
   - Decoder parses the same shape; rejects unknown keys, extra fields, or out-of-order entries with `CodecError.InvalidShape`.
   - Implement by hand (no third-party library); the shape is trivial (5 ints -> string/int values).
3. `ApprovalLinkBuilder`:
   - `@Inject constructor(rng, kdf: KeyDeriver, cipher: AesGcmCipher, codec: ApprovalPayloadCodec, clock: Clock)`.
   - `fun build(kPair: ByteArray, request: UnlockRequest, grantDurationMinutes: Int = 15): Uri`.
   - Derives `kReq = kdf.hkdfSha256(kPair, salt = "krypt/v1/approve".toByteArray(), info = request.requestId.toString().toByteArray() + request.salt, outLength = 32)`.
   - Generates 12-byte nonce via `rng`.
   - Builds payload, encodes CBOR, encrypts, concatenates `nonce || ciphertext || tag`.
   - Assembles URI: `krypt://approve?v=1&req=<uuid>&data=<b64url>&iat=<epoch>`.
4. `ApprovalLinkParser` (shape-only): scheme/authority/version/UUID/Base64; does NOT decrypt. Decryption lives in `ApprovalConsumer` (T015) which has access to DB state.

**Validation.**
- Build an approval for a known `kPair` + `UnlockRequest`; verify the URL fits in <300 chars.
- `ApprovalPayloadCodec` round-trip for a hundred random payloads.
- CBOR encode with an unknown tag triggers decoder failure.

### T015 - ApprovalConsumer (pure business logic)

**Purpose.** Combines parse -> DB lookup -> key derivation -> decrypt -> consistency check -> outcome. Stays free of Android platform types (takes repositories + crypto as constructor deps); `GuardianActivity` (WP14) wires it into the UI.

**Files to create:**
- `app/src/main/java/com/krypt/app/deeplink/ApprovalConsumer.kt`
- `app/src/main/java/com/krypt/app/deeplink/ApprovalOutcome.kt`
- `app/src/main/java/com/krypt/app/deeplink/ApprovalError.kt` (sealed per contract)

**Implementation steps:**
1. `ApprovalOutcome` data class: `requestId: UUID, targetPackage: String, grantExpiresAt: Long`.
2. `ApprovalError` sealed interface: `BadScheme, WrongVersion, MissingParam(name), BadBase64, BadUuid, UnmatchedRequest, RequestExpired, CipherDecryptFailed, PayloadInconsistent`.
3. `ApprovalConsumer`:
   - Constructor-injected: `KeyDeriver, AesGcmCipher, ApprovalPayloadCodec, OutstandingRequestRepository (from WP06), UnlockGrantRepository (WP06), KPairStore (WP06), Clock`.
   - `suspend fun consume(uri: Uri, now: Long): Result<ApprovalOutcome, ApprovalError>` on `Dispatchers.Default`.
   - Pipeline per `contracts/approve.md` "Parsing and consumption" section, steps 1-10.
   - In step 8, use a single Room `@Transaction` method on `OutstandingRequestDao` that atomically checks-and-flips `consumed` and inserts a new `UnlockGrant`. If the atomic flip returns 0 rows affected -> `UnmatchedRequest`.
   - Returns `Ok(ApprovalOutcome)` with `grantExpiresAt = now + payload.durMin * 60_000L`.

**Validation.**
- Each error case from `contracts/approve.md` has a targeted unit test via fakes.
- Double-consume returns `UnmatchedRequest` on the second call (race covered by the atomic DAO method).

### T016 - JVM round-trip + error-case tests

**Purpose.** Exhaustive coverage.

**Files to create:**
- `app/src/test/java/com/krypt/app/deeplink/UnlockRequestRoundTripTest.kt`
- `app/src/test/java/com/krypt/app/deeplink/ApprovalRoundTripTest.kt`
- `app/src/test/java/com/krypt/app/deeplink/ApprovalPayloadCodecTest.kt`
- `app/src/test/java/com/krypt/app/deeplink/ApprovalConsumerTest.kt`

**Implementation steps:**
1. `UnlockRequestRoundTripTest`: build -> parse round-trip; each `RequestParseError` produced by targeted corruption.
2. `ApprovalRoundTripTest`: build approval for `(kPair, request)` -> consume with valid state in a fake repo -> `Ok(ApprovalOutcome)` with matching fields. 100 random-input runs for coverage.
3. `ApprovalPayloadCodecTest`: CBOR encode/decode; unknown-tag rejection; truncated buffer rejection; oversized buffer (trailing bytes) rejection.
4. `ApprovalConsumerTest`: drives all error branches using fake `OutstandingRequestRepository` + fake `UnlockGrantRepository` + fake `KPairStore`. Each `ApprovalError` asserted exactly once.

**Validation.**
- `./gradlew :app:testDebugUnitTest --tests "com.krypt.app.deeplink.*"` green.
- Branch coverage on `ApprovalConsumer.consume` >= 95% (measured via JaCoCo or kover - add the Gradle plugin if not yet).

## Test Strategy

- **Unit (JVM):** T016 exhaustive.
- **Integration:** Deferred to WP15 end-to-end test (Subject overlay -> request URL -> Guardian -> approval URL -> overlay dismiss).
- **Performance:** `ApprovalRoundTripTest` should complete 100 runs in <1 second; if not, profile.

## Definition of Done

- [ ] All listed files compile and pass tests.
- [ ] `ApprovalConsumer.consume` returns each of 9 error cases on targeted inputs, verified by test.
- [ ] URL-size-budget test: sample request URL <=260 chars; sample approval URL <=280 chars (asserted in test).
- [ ] Double-consume race: two parallel `consume` calls on the same URL yield exactly one `Ok` and one `UnmatchedRequest` (test uses `Dispatchers.Default` + `async`).
- [ ] No Android-framework imports in `UnlockRequestBuilder` / `ApprovalLinkBuilder` / `ApprovalPayloadCodec` / `ApprovalConsumer` core logic; only `android.net.Uri` at the parser edges.

## Risks + Edge cases

- **Hand-rolled CBOR.** A 5-field fixed-shape codec is low-risk but still a crypto boundary - ensure the decoder rejects ANY deviation from the exact encoding shape to prevent canonicalisation attacks. Recommend a fuzz-style property test (1000 random byte buffers -> expect exception or valid decode, no crashes).
- **UUID parsing quirks.** `UUID.fromString` accepts any 36-char UUID including non-v4. Add a `BadUuid` guard: `require(uuid.version() == 4)`.
- **Package-name regex.** Some legitimate package names contain digits after dots: `com.app42.foo`. Be generous: `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$`. Reject packages that contain only a single segment (no dots) - not a valid Android package.
- **Clock skew.** Subject and Guardian devices may have slightly-different wall clocks. `now > iat + ttl` check on the Guardian side should tolerate up to 60 s of future-dated requests (Subject's clock ahead) and up to 60 s of past-dated (Guardian's clock behind). Make the tolerance a config constant.
- **Repository dependency direction.** `ApprovalConsumer` depends on repositories defined in WP06, but its own test file uses fakes. Make sure the fakes live under `src/test/` so WP06 doesn't have to exist before WP03 tests can compile. Alternative: define repository **interfaces** here, real impls in WP06.

## Reviewer Guidance

- Eyeball every error-path branch in `ApprovalConsumer.consume` and confirm a test exists.
- Verify `ApprovalPayloadCodec` has a round-trip property test (at least 100 random inputs).
- Confirm the URL-size assertion is real (not `assumeTrue`).
- Check that `MessageDigest.isEqual` (or equivalent constant-time comparison) is used for the GCM-tag pass/fail distinction surface - though GCM itself is constant-time internally, any metadata comparison should be too.

## Next command

```
polaris implement WP03 --base WP02
```

## Activity Log

- 2026-04-24T06:42:00Z -- claude -- shell_pid=11380 -- lane=doing -- Assigned agent via workflow command
- 2026-04-24T06:55:44Z -- claude -- lane=doing -- WP03 crypto deep-links impl started
- 2026-04-24T06:56:08Z -- claude -- lane=testing -- 43 tests authored across 4 files
- 2026-04-24T06:56:17Z -- claude -- lane=for_review -- WP03 ready. Commit on 001-krypt-app-locker-WP03 (based on WP02). 22 source files: Outcome, Clock, CoreModule, KPairStore (shared w/ WP04), Outstanding+UnlockGrant domain + repo interfaces, DeepLinkScheme + Base64Url + UrlCodec, UnlockRequest/Builder/Parser + RequestParseError, ApprovalPayload/Codec/LinkBuilder/LinkParser + ApprovalError/Outcome + ApprovalConsumer. 43 JVM unit tests: request round-trip (14), CBOR codec (10 incl 100-random property test), approval round-trip (9 incl tamper/replay/NotPaired/wrong-KPair/expiry), consumer error branches (9). All JVM-portable (String URLs, not android.net.Uri). Reviewer runs ./gradlew :app:testDebugUnitTest.
- 2026-04-24T07:04:24Z -- claude -- lane=done -- Merged to main (7a4bde9-style merge)
- 2026-04-24T10:52:19Z – claude – lane=done – All WPs implemented and reviewed; feature accepted
