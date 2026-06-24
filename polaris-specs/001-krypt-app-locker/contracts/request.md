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

Subject-device-to-Guardian-device unlock request. Emitted when a Subject taps "Ask Guardian" in the Locker overlay. Carries enough information for the Guardian device to prompt its user for the PIN and compose an approval.

## URL format

```
krypt://request?v=1
               &req=<requestId:uuid-v4>
               &app=<package-name:max 128 ascii>
               &salt=<b64url: 16-byte request-specific salt>
               &iat=<epoch seconds>
               &ttl=<seconds, default 1800>
```

Note: No `proof` field (see `data-model.md` R4/Design-B rationale). The Subject cannot compute a proof because it does not know the PIN. The approval's authenticity is carried by AES-GCM under a key derived from the persisted `K_pair` + request-specific salt.

## Size budget

Typical length: ~200 chars. Max 260 chars.

## Field semantics

| Field | Type | Purpose |
|-------|------|---------|
| `v` | int | Protocol version `1`. |
| `req` | UUIDv4 | Request identifier. Written to `OutstandingRequest.requestId`. Approval's `req` must match one of these to be accepted. |
| `app` | package name string | Target app to unlock. Shown to Guardian for informed consent. |
| `salt` | 16 bytes, base64url | Per-request random salt. Combined with `K_pair` to derive the AES-256 key used to encrypt the approval payload. |
| `iat` | epoch seconds | Issued-at. |
| `ttl` | seconds | Validity window. Default 30 minutes. |

## Generation (Subject device)

```
suspend fun buildUnlockRequest(
  targetPackage: String,
  kPair: ByteArray,                 // recalled from storage (not used here, just noted for context)
  repo: OutstandingRequestRepo
): Pair<Uri, OutstandingRequestEntity> = withContext(Dispatchers.Default) {
  val requestId = UUID.randomUUID()
  val salt = SecureRandom().nextBytes(16)
  val iat = System.currentTimeMillis() / 1000L
  val ttl = 1800L

  val row = OutstandingRequestEntity(
    requestId = requestId.toString(),
    targetPackage = targetPackage,
    requestSalt = salt,
    issuedAt = iat * 1000, expiresAt = (iat + ttl) * 1000,
    consumed = false
  )
  repo.insert(row)

  val uri = Uri.Builder().scheme("krypt").authority("request")
    .appendQueryParameter("v", "1")
    .appendQueryParameter("req", requestId.toString())
    .appendQueryParameter("app", targetPackage)
    .appendQueryParameter("salt", b64url(salt))
    .appendQueryParameter("iat", iat.toString())
    .appendQueryParameter("ttl", ttl.toString())
    .build()

  uri to row
}
```

## Parsing (Guardian device)

```
data class UnlockRequest(
  val requestId: UUID, val targetPackage: String,
  val salt: ByteArray, val issuedAt: Long, val ttlSeconds: Long
)

sealed interface RequestParseError {
  object BadScheme : RequestParseError
  object WrongVersion : RequestParseError
  data class MissingParam(val name: String) : RequestParseError
  object BadBase64 : RequestParseError
  object BadUuid : RequestParseError
  object BadPackageName : RequestParseError
  object Expired : RequestParseError
}

fun parseUnlockRequest(uri: Uri, now: Long): Result<UnlockRequest, RequestParseError>
```

Contract:
- Scheme must be `krypt`, authority must be `request`.
- `v` must be `"1"`.
- `req` must parse as UUIDv4.
- `app` must match `^[a-z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$` (valid Java package name, Android convention).
- `salt` base64-decoded must be exactly 16 bytes.
- `iat + ttl >= now` (seconds).

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
