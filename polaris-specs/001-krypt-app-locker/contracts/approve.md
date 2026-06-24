# Contract: `krypt://approve`

> **Amendment 1 (2026-04-24)**: Encryption key is now derived from
> `MasterKey` (PBKDF2(PIN, setup_salt, >=300k)) instead of `K_pair`. The
> `data` blob layout changes from `nonce(12) || ct || tag` to
> `nonceForHkdf(16) || aesNonce(12) || ct || tag`. `K_req` is derived as
> `HKDF-SHA-256(MasterKey, "krypt/v1/approve", requestId.utf8 ||
> nonceForHkdf, 32)`. The Subject device has the MasterKey from setup and
> decrypts **silently** (no PIN keypad on Subject side, FR-018). See
> `WP20-amendment1-crypto-url-rework.md` in tasks/.

Guardian-device-to-Subject-device unlock approval. Emitted after the Guardian enters a valid PIN in the Guardian Popup. Carries an AES-256-GCM-encrypted grant payload that the Subject can decrypt using its stored MasterKey plus the matched `OutstandingRequest`.

## URL format

```
krypt://approve?v=1
               &req=<requestId:uuid-v4>
               &data=<b64url: AES-256-GCM(nonce||ciphertext||tag) of ApprovalPayload>
               &iat=<epoch seconds>
```

## Size budget

Typical length: ~230 chars. Max 280 chars.

## Field semantics

| Field | Type | Purpose |
|-------|------|---------|
| `v` | int | Protocol version `1`. |
| `req` | UUIDv4 | Echoes the `req` from the matched request. Subject uses this as the `OutstandingRequest` lookup key. |
| `data` | base64url binary | AES-256-GCM sealed box. Plaintext is CBOR-encoded `ApprovalPayload`. 12-byte nonce prepended, 16-byte GCM tag appended. |
| `iat` | epoch seconds | Issued-at (Guardian clock). |

## `ApprovalPayload` (plaintext inside `data`)

Minimal CBOR map:

```
{
  1: <string>  // "v": protocol version (redundant but belt-and-suspenders)
  2: <string>  // "req": same UUID (binds ciphertext to request ID)
  3: <string>  // "app": target package (binds grant to app)
  4: <int>     // "durMin": grant duration in minutes (default 15)
  5: <int>     // "iat": epoch seconds when grant was issued
}
```

Typical plaintext length: ~65 bytes. Ciphertext (with nonce+tag): ~93 bytes. Base64url: ~124 chars.

## Generation (Guardian device)

```
suspend fun buildApproval(
  matchedRequest: UnlockRequest,
  kPair: ByteArray,                 // from EncryptedSharedPreferences
  grantDurationMinutes: Int = 15
): Uri = withContext(Dispatchers.Default) {
  val kReq = Hkdf.derive(
    ikm = kPair,
    salt = /* HKDF salt = */ "krypt/v1/approve".encodeToByteArray(),
    info = /* HKDF info = */ matchedRequest.requestId.toString().encodeToByteArray() + matchedRequest.salt,
    outLength = 32
  )

  val plaintext = Cbor.encode(ApprovalPayload(
    v = "1",
    req = matchedRequest.requestId.toString(),
    app = matchedRequest.targetPackage,
    durMin = grantDurationMinutes,
    iat = System.currentTimeMillis() / 1000L
  ))

  val nonce = SecureRandom().nextBytes(12)
  val cipher = AesGcmCipher.encrypt(kReq, nonce, plaintext)
  val data = nonce + cipher  // cipher already includes the 16-byte tag per JCE convention

  Uri.Builder().scheme("krypt").authority("approve")
    .appendQueryParameter("v", "1")
    .appendQueryParameter("req", matchedRequest.requestId.toString())
    .appendQueryParameter("data", b64url(data))
    .appendQueryParameter("iat", (System.currentTimeMillis() / 1000L).toString())
    .build()
}
```

## Parsing and consumption (Subject device)

```
data class ApprovalOutcome(
  val requestId: UUID,
  val targetPackage: String,
  val grantExpiresAt: Long  // epoch ms
)

sealed interface ApprovalError {
  object BadScheme : ApprovalError
  object WrongVersion : ApprovalError
  data class MissingParam(val name: String) : ApprovalError
  object BadBase64 : ApprovalError
  object BadUuid : ApprovalError
  object UnmatchedRequest : ApprovalError    // no OutstandingRequest with that req, OR consumed already
  object RequestExpired : ApprovalError
  object CipherDecryptFailed : ApprovalError // AES-GCM authentication failure
  object PayloadInconsistent : ApprovalError // CBOR req/app inside doesn't match URL req / OR.targetPackage
}

suspend fun consumeApproval(
  uri: Uri,
  now: Long,
  outstandingRepo: OutstandingRequestRepo,
  grantRepo: UnlockGrantRepo,
  sessionStore: LockerSessionStore,
  kPair: ByteArray
): Result<ApprovalOutcome, ApprovalError> = withContext(Dispatchers.Default) {
  // 1. Shape-parse the URL.
  // 2. Look up OutstandingRequest by req. If absent or consumed -> UnmatchedRequest.
  // 3. Check OutstandingRequest.expiresAt > now; else RequestExpired.
  // 4. Derive K_req via HKDF(kPair, "krypt/v1/approve", req || salt).
  // 5. AES-GCM decrypt `data` with K_req. Failure -> CipherDecryptFailed.
  // 6. CBOR parse plaintext. Verify payload.req == URL req AND payload.app == OR.targetPackage.
  //    Else PayloadInconsistent.
  // 7. Compute expiresAt = now + payload.durMin * 60_000.
  // 8. In a single Room transaction: mark OR consumed=true, insert UnlockGrant.
  // 9. sessionStore.recordGrant(app, expiresAt).
  // 10. Return ApprovalOutcome.
}
```

## Cryptographic invariants

- `K_req` is derived from `K_pair` and the request-specific salt via HKDF-SHA-256. It is a per-request 256-bit key.
- AES-256-GCM provides confidentiality + authenticity. Any mutation of `data` fails the GCM tag check (`CipherDecryptFailed`).
- The plaintext re-carries `req` and `app` so that even an attacker who somehow forged `data` with a valid GCM tag (impossible without `K_req`) cannot swap approvals between requests.
- `iat` on the URL is for logging only; the grant's actual validity comes from `OutstandingRequest.expiresAt` (existing window) truncated by `grantedAt + durMin` (new window from plaintext).

## Replay protection

- `OutstandingRequest.consumed` is set true on first successful consumption. A second attempt to consume the same approval URL returns `UnmatchedRequest`.
- Transaction-safe Room update: `UPDATE outstanding_requests SET consumed = 1 WHERE requestId = ? AND consumed = 0` - if 0 rows affected, `UnmatchedRequest`.

## Threat-model notes

- Attacker captures the URL: they see `req`, a ciphertext blob, and `iat`. They cannot decrypt (no `K_pair`), cannot replay (Subject device marks consumed once, and on restart reloads `consumed=1` from Room), cannot forge a different approval (GCM tag).
- Attacker captures the URL and tries to bind it to a different Subject device: the other Subject's `K_pair` is different, decrypt fails.
- Guardian device compromise: attacker obtains `K_pair` and can forge arbitrary approvals. Mitigation is out of v1 scope; Device-Admin on Guardian side + biometric-gate to unlock the Guardian app is a future hardening.

## Error handling (UI)

| Error | Subject UI response |
|-------|---------------------|
| `UnmatchedRequest` | "This approval doesn't match any of your pending unlock requests." |
| `RequestExpired` | "Your original request expired before this approval arrived. Please ask again." |
| `CipherDecryptFailed`, `PayloadInconsistent` | "This approval failed cryptographic verification. It may have been tampered with." |
| `BadBase64`, `MissingParam`, etc. | "Could not read this approval." |

## Success UX

On `ApprovalOutcome`, the Locker overlay for `targetPackage` is dismissed immediately, and a toast shows "Unlocked until {time}". The unlock is recorded in `UnlockGrant` and visible to the Subject in a read-only history screen (future).
