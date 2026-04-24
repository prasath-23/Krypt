# Contract: `krypt://paired`

Guardian-device-to-Subject-device pairing reply. Completes the pairing initiated by `krypt://pair`. Also used to rotate the Guardian's public salt on PIN change.

## URL format

```
krypt://paired?v=1
              &sub=<subjectId:uuid-v4>
              &guardianName=<url-encoded display name, max 64 chars>
              &pubSalt=<b64url: 32-byte Guardian public salt>
              &ephPub=<b64url: 32-byte X25519 ephemeral public key>
              &kdfIter=<int, Guardian's calibrated iteration count>
              &iat=<epoch seconds>
              &ttl=<seconds, default 900>
              &mac=<b64url: 32-byte HMAC-SHA-256 tag>
```

All parameters are required. No trailing fragment.

## Size budget

Typical length: ~360 chars. Max 440 chars.

## Field semantics

| Field | Type | Purpose |
|-------|------|---------|
| `v` | int | Protocol version `1`. |
| `sub` | UUIDv4 | Echoes the `sub` from the incoming `krypt://pair`. Subject validates match. |
| `guardianName` | URL-encoded string | Friendly name shown on the Subject device ("Dad's Phone"). |
| `pubSalt` | 32 bytes, base64url | Guardian's persistent PIN-KDF salt. Persists until PIN rotation. |
| `ephPub` | 32 bytes, base64url | Guardian's X25519 ephemeral public key. Combined with Subject's `ephPriv` via X25519 to derive `K_pair`. |
| `kdfIter` | int >= 300000 | Guardian's calibrated PBKDF2 iteration count. Subject records for its own diagnostics but does not use (PIN is never typed on Subject). |
| `iat` | epoch seconds | Issued-at. |
| `ttl` | seconds | Validity window, default 15 min. |
| `mac` | 32 bytes, base64url | HMAC-SHA-256 tag over `pubSalt || ephPub || kdfIter || iat || ttl || sub`, keyed by `K_pair`. Proves Guardian actually derived `K_pair` from the Subject's `ephPub`. Defends against a passive MITM substituting another `ephPub`. |

## Generation (Guardian device)

```
fun buildPairedReply(
  incomingRequest: PairRequest,
  guardianDisplayName: String,
  guardianEphPrivate: X25519PrivateKey,
  guardianPubSalt: ByteArray,          // 32 random bytes generated at first PIN set, rotated on PIN change
  guardianKdfIterations: Int
): Uri {
  val guardianEphPub = X25519.derivePublic(guardianEphPrivate)
  val kPair = X25519.agree(guardianEphPrivate, incomingRequest.subjectEphPub)
  // kPair is 32 bytes. Persist both sides of the pairing; used to derive K_req later.

  val iat = System.currentTimeMillis() / 1000L
  val ttl = 900L
  val macInput = byteArrayOf() +
      guardianPubSalt +
      guardianEphPub +
      guardianKdfIterations.toByteArray() +
      iat.toByteArray() +
      ttl.toByteArray() +
      incomingRequest.subjectId.toByteArray()
  val mac = HmacSha256(kPair, macInput)

  return Uri.Builder().scheme("krypt").authority("paired")
      .appendQueryParameter("v", "1")
      .appendQueryParameter("sub", incomingRequest.subjectId.toString())
      .appendQueryParameter("guardianName", guardianDisplayName)
      .appendQueryParameter("pubSalt", b64url(guardianPubSalt))
      .appendQueryParameter("ephPub", b64url(guardianEphPub))
      .appendQueryParameter("kdfIter", guardianKdfIterations.toString())
      .appendQueryParameter("iat", iat.toString())
      .appendQueryParameter("ttl", ttl.toString())
      .appendQueryParameter("mac", b64url(mac))
      .build()
}
```

## Parsing (Subject device)

```
data class PairedReply(
  val subjectId: UUID, val guardianDisplayName: String,
  val guardianPubSalt: ByteArray, val guardianEphPub: ByteArray,
  val kdfIterations: Int, val issuedAt: Long, val ttlSeconds: Long,
  val mac: ByteArray
)

sealed interface PairedParseError {
  object BadScheme : PairedParseError
  object WrongVersion : PairedParseError
  data class MissingParam(val name: String) : PairedParseError
  object BadBase64 : PairedParseError
  object WrongSubjectId : PairedParseError
  object Expired : PairedParseError
  object BadMac : PairedParseError
}

fun parseAndVerifyPairedReply(
  uri: Uri,
  now: Long,
  myEphPrivate: X25519PrivateKey,
  myExpectedSubjectId: UUID
): Result<Pair<PairedReply, ByteArray /* K_pair */>, PairedParseError>
```

Contract:
- All shape validation per `pair.md` rules.
- `sub` MUST match `myExpectedSubjectId` or returns `WrongSubjectId`.
- Derives `K_pair = X25519.agree(myEphPrivate, guardianEphPub)`.
- Verifies `mac` against the canonical MAC input. Returns `BadMac` on mismatch.
- `now > iat + ttl` returns `Expired`.
- On success: returns `PairedReply` and the derived `K_pair` (the caller persists `K_pair` in EncryptedSharedPreferences).

## Cryptographic invariants

- `K_pair` is the X25519 shared secret between Subject's and Guardian's ephemeral keypairs. It is 32 bytes, has full Curve25519 security, and is symmetric between both sides.
- `mac` cryptographically binds `pubSalt`, `ephPub`, `kdfIter`, `iat`, `ttl`, and `sub` under `K_pair`, preventing an attacker who substitutes a different `ephPub` mid-flight from also constructing a valid `mac`.
- `K_pair` is used to derive per-request AES-256 keys via `HKDF(K_pair, requestId || requestSalt)`. Direct use of `K_pair` for AES is forbidden.

## PIN rotation via `krypt://paired`

The same URL contract is reused when the Guardian rotates their PIN:

1. Guardian device generates a fresh random `guardianPubSalt`.
2. Guardian device uses the **persisted** `K_pair` from original pairing (not a new ephemeral exchange) to generate a fresh `krypt://paired` URL carrying the new `pubSalt`.
3. Subject device parses, verifies the `mac` against the stored `K_pair`, and overwrites `GuardianPairing.pubSalt`.
4. All outstanding `OutstandingRequest` rows on the Subject remain valid at the *protocol* level (they don't reference `pubSalt` directly - they reference `K_pair` which hasn't rotated), but the Guardian cannot produce a new valid proof for these requests because their `pubSalt` changed. In practice, the Subject SHOULD purge `OutstandingRequest`s on PIN rotation to force Guardian-synchronised state.

Note: PIN rotation with re-use of `K_pair` is acceptable because `K_pair` is an X25519 shared secret not derived from the PIN - rotating the PIN does not compromise `K_pair`.

## Error handling (UI)

| Error | UI response |
|-------|-------------|
| `BadMac` | "This pairing reply could not be verified. Your Guardian's device may be compromised, OR the link was corrupted in transit. Ask them to generate a new pairing." |
| `WrongSubjectId` | "This pairing reply is for a different Subject device." |
| `Expired` | "This pairing reply has expired." |
| `BadBase64`, `MissingParam`, `WrongVersion` | "This pairing reply appears corrupted." |
