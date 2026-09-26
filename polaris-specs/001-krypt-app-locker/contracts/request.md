# Contract: `krypt://request`

> **Amendment 1 (2026-04-24)**: URL shape now carries the Subject's *setup*
> salt (the 16-byte salt PBKDF2 was run against at Guardian-on-Subject PIN
> setup, NOT a per-request random value) alongside a 32-byte `pinProof`
> (`HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")`). The Guardian types a
> PIN, recomputes `MasterKey = PBKDF2(PIN, salt, >=300k)` and then
> `HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")`, and constant-time
> compares against the URL's `pinProof`. Default TTL drops from 1800 s to
> 300 s. Per-request randomness that binds a specific approval-to-request
> lives inside the approval's `data` blob (`nonceForHkdf`), not here.
> See `WP20-amendment1-crypto-url-rework.md` in tasks/.
>
> **Amendment 2 (2026-09-26)**: the URL gains `kdfIter`, the PBKDF2 iteration count the
> Subject calibrated at PIN setup. The Guardian must derive with this count; its own local
> setting can differ, which made the correct PIN fail. `UnlockRequestIssuer` now also saves
> the `OutstandingRequest` row when it builds the URL. The URL format, field table, generation
> and parsing sections below describe the current shape. The "Cryptographic invariants" and
> "Threat-model notes" sections still describe the pre-Amendment-1 `K_pair` design.

Subject-device-to-Guardian-device unlock request. Emitted when a Subject taps "Ask Guardian" on Krypt's lock screen. Carries enough information for the Guardian device to prompt its user for the PIN and compose an approval.

## URL format

```
krypt://request?v=1
               &req=<requestId:uuid-v4>
               &app=<package-name:max 128 ascii>
               &salt=<b64url: 16-byte setup salt>
               &pinProof=<b64url: 32-byte HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")>
               &kdfIter=<int: PBKDF2 iterations used at setup>
               &iat=<epoch seconds>
               &ttl=<seconds, default 300>
```

## Size budget

Typical length: ~230 chars. Must stay under 512 chars.

## Field semantics

| Field | Type | Purpose |
|-------|------|---------|
| `v` | int | Protocol version `1`. |
| `req` | UUIDv4 | Request identifier. Written to `OutstandingRequest.requestId`. Approval's `req` must match one of these to be accepted. |
| `app` | package name string | Target app to unlock. Shown to Guardian for informed consent. |
| `salt` | 16 bytes, base64url | Setup salt the PIN was PBKDF2'd against at PIN setup (not per-request). |
| `pinProof` | 32 bytes, base64url | `HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")`. The Guardian recomputes it from the typed PIN and constant-time compares. |
| `kdfIter` | int, 300000..10000000 | PBKDF2-HMAC-SHA256 iteration count used at setup. The Guardian MUST derive with this value. |
| `iat` | epoch seconds | Issued-at. |
| `ttl` | seconds | Validity window. Default 300 (5 minutes, FR-019). |

## Generation (Subject device)

`UnlockRequestIssuer.issue(targetPackage)`:

1. Load `salt` and `pinProof` from `MasterKeyStore`. If either is missing, fail with `NotConfigured`.
2. Read the calibrated iteration count from `SettingsRepository` (the same value used at PIN setup).
3. Build the URL with `UnlockRequestBuilder` (fresh UUIDv4, `iat` = now, `ttl` = 300).
4. Save the matching `OutstandingRequest` row with `consumed = false`, `issuedAtMs = iat * 1000`, and `expiresAtMs = (iat + ttl) * 1000`. `ApprovalConsumer` can only accept an approval whose `req` matches such a row.

## Parsing (Guardian device)

```
data class UnlockRequest(
  val requestId: UUID, val targetPackage: String,
  val salt: ByteArray, val pinProof: ByteArray,
  val issuedAt: Long, val ttlSeconds: Long, val kdfIterations: Int
)

fun UnlockRequestParser.parse(url: String, nowSeconds: Long): Outcome<UnlockRequest, RequestParseError>
```

Contract:
- Scheme must be `krypt`, authority must be `request`.
- `v` must be `"1"`.
- `req` must parse as UUIDv4.
- `app` must match `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$` (valid Java package name, Android convention).
- `salt` base64-decoded must be exactly 16 bytes; `pinProof` exactly 32 bytes.
- `kdfIter`, when present, must be an integer in 300000..10000000 (`BadKdfIterations` otherwise). When absent (a request from a build older than Amendment 2), the parser uses 300000.
- `iat + ttl >= now` (seconds), with 60 s of clock-skew tolerance.

## Cryptographic invariants

- Neither the Subject nor the Guardian can derive the per-request AES key `K_req` from the request URL alone, because `K_req = HKDF(K_pair, req || salt)` and `K_pair` is held only by the two paired devices.
- `salt` is unique per request (`SecureRandom`). Even if a malicious observer replays the URL, the approval they'd receive could be encrypted with a `K_req` they cannot compute.
- `req` is globally unique across all requests from this Subject. Collision probability for UUIDv4 over 10^9 requests is negligible.

## Threat-model notes

- Request URL contains: request ID, target package, a 16-byte random salt, and timestamps. It contains **zero authentication material**. It is deliberately public.
- An attacker who intercepts `krypt://request?...`:
  - Cannot forge an approval (they don't know `K_pair`).
  - Cannot impersonate the Subject in a future pairing (pairing requires X25519 key exchange, not accessible from a request URL).
  - Can observe the target package name (privacy leak). This is accepted as minimal - package names are not secret; the Subject is already sharing them with a messenger app.
- An attacker who intercepts and **replays** the URL to a different Guardian is a no-op: the Guardian is paired to this specific Subject (`subjectId`), and approval-URL `req` must match an `OutstandingRequest` the Subject actually has open.

## Error handling (UI)

| Error | Guardian UI response |
|-------|----------------------|
| `Expired` | "This unlock request has expired. Ask your Subject to send a new one." |
| `BadPackageName` | "This unlock request appears corrupted." |
| `BadScheme`, `WrongVersion`, etc. | "Could not read this unlock request." |
| Valid but this Guardian is not paired with this Subject | "This request is from a Subject you are not paired with ('abc-123'). Ignore or block?" |

## Audit

The Guardian device SHOULD log every parsed `UnlockRequest` (with a bounded ring buffer, e.g. 100 entries) for the human Guardian to review later: "On 2026-04-24 at 10:42 you approved requests for com.whatsapp".
