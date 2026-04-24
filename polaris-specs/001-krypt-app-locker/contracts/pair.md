# Contract: `krypt://pair`

Subject-device-to-Guardian-device first-time pairing request. Emitted **once** during onboarding on the Subject device. The Guardian's `krypt://paired` reply completes the pairing.

## URL format

```
krypt://pair?v=1
            &sub=<subjectId:uuid-v4>
            &subName=<url-encoded display name, max 64 chars>
            &ephPub=<b64url: 32-byte X25519 ephemeral public key>
            &iat=<epoch seconds>
            &ttl=<seconds, default 900>
```

All parameters are required. No trailing fragment.

## Size budget

Typical length: ~190 chars. Max 250 chars.

## Field semantics

| Field | Type | Purpose |
|-------|------|---------|
| `v` | int | Protocol version; always `1` in v1. Newer versions MAY change the shape; parsers MUST fail fast on unknown versions. |
| `sub` | UUIDv4 | Stable Subject identifier. Written to `GuardianPairing.subjectId` on the Guardian device. |
| `subName` | URL-encoded string | Friendly name shown to the Guardian ("Alice's Pixel"). |
| `ephPub` | 32 bytes, base64url(no-pad) | Subject's ephemeral X25519 public key. The private half is held only in memory on the Subject device until the pairing completes. |
| `iat` | epoch seconds | Issued-at timestamp. Used for TTL evaluation. |
| `ttl` | seconds | Validity window. Guardian device MUST reject if `now > iat + ttl`. Default 15 min. |

## Generation (Subject device)

```
fun buildPairRequest(
  subjectId: UUID,
  subjectDisplayName: String,
  ephPrivate: X25519PrivateKey   // generated and held in memory
): Uri {
  val ephPub = X25519.derivePublic(ephPrivate)
  val iat = System.currentTimeMillis() / 1000L
  return Uri.Builder()
    .scheme("krypt").authority("pair")
    .appendQueryParameter("v", "1")
    .appendQueryParameter("sub", subjectId.toString())
    .appendQueryParameter("subName", subjectDisplayName)
    .appendQueryParameter("ephPub", Base64.encodeToString(ephPub, URL_SAFE or NO_WRAP or NO_PADDING))
    .appendQueryParameter("iat", iat.toString())
    .appendQueryParameter("ttl", "900")
    .build()
}
```

## Parsing (Guardian device)

```
data class PairRequest(
  val subjectId: UUID,
  val subjectDisplayName: String,
  val subjectEphPub: ByteArray,
  val issuedAt: Long,
  val ttlSeconds: Long
)

sealed interface PairParseError {
  object BadScheme : PairParseError
  object WrongVersion : PairParseError
  data class MissingParam(val name: String) : PairParseError
  object BadBase64 : PairParseError
  object BadUuid : PairParseError
  object Expired : PairParseError
}

fun parsePairRequest(uri: Uri, now: Long): Result<PairRequest, PairParseError>
```

Contract:
- Returns `BadScheme` if `uri.scheme != "krypt"` or `uri.authority != "pair"`.
- Returns `WrongVersion` if `v != "1"`.
- Returns `MissingParam(name)` if any required param is absent.
- Returns `BadBase64` if `ephPub` decode fails or length != 32.
- Returns `BadUuid` if `sub` is not a v4 UUID.
- Returns `Expired` if `now > iat + ttl`.
- Otherwise `Ok(PairRequest(...))`.

## Cryptographic invariants

- `ephPub` is a valid X25519 point. (Implementation-wise: any 32-byte value is valid as an X25519 public key; no subgroup check needed.)
- The Subject device MUST discard `ephPrivate` after the Guardian's `krypt://paired` reply is consumed, OR after 15 minutes elapse with no reply.

## Trust model

- `krypt://pair` is **unauthenticated**. Anyone who can present such a link to the Guardian app can trigger a pairing prompt. This is acceptable because:
  1. Pairing completion requires the human Guardian to explicitly approve ("Pair with 'Alice's Pixel'? [Yes] [No]") in the Guardian Popup UI.
  2. After pairing, the Guardian device is immutable-paired to that Subject. Re-pair requires the Guardian to manually unpair via Settings first.
  3. The ephemeral key established here is used only to derive `K_pair`; a MITM who substitutes a different `ephPub` cannot derive the same `K_pair` as the honest parties.

## Error handling (UI)

| Error | UI response |
|-------|-------------|
| `BadScheme`, `WrongVersion`, `BadBase64`, `BadUuid`, `MissingParam` | "This pairing link appears corrupted. Ask your Subject to generate a new pairing code." |
| `Expired` | "This pairing link has expired. Ask your Subject to generate a new code." |
| Already paired with a different Subject | "This Guardian device is already paired with 'Bob's Device'. Unpair in Settings first to pair with 'Alice's Pixel'." |
