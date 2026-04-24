# Phase 1 Data Model - Krypt

Concrete shape of every entity in the app. Persistence choices are noted per entity.

---

## Persistence layers

| Layer | Backing store | Purpose |
|-------|---------------|---------|
| Room DB (`KryptDatabase`) | SQLite file `krypt.db` under app's private storage | LockedApp, GuardianPairing, OutstandingRequest, UnlockGrant |
| DataStore (`krypt.preferences_pb`) | Proto-DataStore (scalar settings) | KDF iteration count (calibrated), onboarding-complete flag, default grant-duration minutes |
| EncryptedSharedPreferences (Guardian device only) | Android Keystore-backed | Guardian's persistent `pub_salt` + `priv_salt_for_proof` at rest |
| In-memory | Process heap | Active `LockerSession`s (rebuilt on process start from `UnlockGrant` table filtered by `expiresAt > now`) |

---

## Entity: `LockedApp`

**Purpose.** The canonical list of packages whose launch is intercepted by the overlay.

**Persistence.** Room `@Entity(tableName = "locked_apps")`.

```
packageName    TEXT    PRIMARY KEY
displayName    TEXT    NOT NULL
iconUri        TEXT    NULL         -- content:// URI of an exported icon blob (optional perf cache)
lockState      TEXT    NOT NULL     -- enum: LOCKED, UNLOCKED
lockSource     TEXT    NOT NULL     -- enum: DEFAULT_DENY, MANUAL
createdAt      INTEGER NOT NULL     -- epoch ms
updatedAt      INTEGER NOT NULL     -- epoch ms
```

**Invariants.**
- `lockState = UNLOCKED` is transient and only set by a successful `UnlockGrant` consumption. It is expected to flip back to `LOCKED` by a scheduled job or by consulting `UnlockGrant.expiresAt` on every read.
- New rows inserted by `PackageReceiver` always use `lockSource = DEFAULT_DENY`.
- Rows can be added manually by the Administrator UI (future) with `lockSource = MANUAL`.

**DAO operations.**

```
@Dao interface LockedAppDao {
  @Insert(onConflict = REPLACE) suspend fun insert(app: LockedAppEntity)
  @Query("SELECT * FROM locked_apps WHERE packageName = :pkg")
  suspend fun findByPackage(pkg: String): LockedAppEntity?
  @Query("SELECT * FROM locked_apps ORDER BY displayName")
  fun observeAll(): Flow<List<LockedAppEntity>>
  @Query("UPDATE locked_apps SET lockState = :state, updatedAt = :ts WHERE packageName = :pkg")
  suspend fun updateLockState(pkg: String, state: LockState, ts: Long)
}
```

**Repository seam (FR-016).** `LockedAppsRepository` exposes:

```
suspend fun checkIfAppIsLocked(pkg: String): Boolean
suspend fun lockNewlyInstalledApp(pkg: String, displayName: String)
fun observeLockedApps(): Flow<List<LockedApp>>
suspend fun unlockAppUntil(pkg: String, expiresAtMs: Long)
```

Foundational code ships a `FakeLockedAppsRepository` (in-memory map) used by `PackageReceiverTest` and `AppLockerAccessibilityServiceTest`; the production binding is `RoomLockedAppsRepository`.

---

## Entity: `GuardianPairing`

**Purpose.** Records the Guardian this device is paired with. Exactly one row on a Subject device; exactly one row on a Guardian device (but populated with different semantics).

**Persistence.** Room `@Entity(tableName = "guardian_pairing")` plus duplicated copy of the `pubSalt` in EncryptedSharedPreferences on the Guardian device.

```
id                INTEGER PRIMARY KEY  -- always 1 (single-row table)
role              TEXT    NOT NULL     -- enum: SUBJECT_OF_GUARDIAN, GUARDIAN_OF_SUBJECT
remoteDisplayName TEXT    NOT NULL     -- friendly name of the other device's user
pubSalt           BLOB    NOT NULL     -- 32 bytes, persistent across requests; rotated on PIN change
kdfIterations     INTEGER NOT NULL     -- calibrated iterations, set at onboarding
pairedAt          INTEGER NOT NULL     -- epoch ms
```

**Invariants.**
- `role = SUBJECT_OF_GUARDIAN` means "this device is being locked; `pubSalt` is the public salt of our Guardian's PIN KDF".
- `role = GUARDIAN_OF_SUBJECT` means "this device is the Guardian; `pubSalt` is MY public salt, broadcast to my paired Subject".
- On the Guardian device ONLY, the **private** component needed to reproduce `proof = KDF(PIN, pubSalt | requestSalt)` is: the PIN (user-typed, never stored) + `pubSalt` (stored in clear) + per-request `requestSalt` (received in the URL).
- On the Subject device, `pubSalt` is stored in Room AND a copy kept in a memory cache owned by the singleton `GuardianRepository` for fast lookup during interception.
- PIN rotation flow: Guardian device generates fresh random `pubSalt`, updates its own row, then emits `krypt://paired` carrying the new `pubSalt` to the Subject. Subject overwrites its row. Any outstanding `OutstandingRequest` rows on the Subject are now uncomputable (Guardian cannot produce a valid proof for the old salt) - subject must re-issue.

---

## Entity: `OutstandingRequest`

**Purpose.** Tracks a request the Subject has emitted but for which no approval has arrived yet. Used to (a) reject unmatched approvals (FR-015) and (b) show "waiting for Guardian" state in the Locker Screen.

**Persistence.** Room `@Entity(tableName = "outstanding_requests")`.

```
requestId      TEXT    PRIMARY KEY  -- UUID v4 generated at request time
targetPackage  TEXT    NOT NULL
requestSalt    BLOB    NOT NULL     -- 16 random bytes per request
proof          BLOB    NOT NULL     -- SHA-256(PBKDF2(PIN placeholder, pubSalt | requestSalt, iterations)) -- see note below
issuedAt       INTEGER NOT NULL
expiresAt      INTEGER NOT NULL     -- typically issuedAt + 30 minutes
consumed       INTEGER NOT NULL     -- 0/1 boolean: has an approval been matched to this request
```

**Note on `proof`.** The Subject device cannot compute `proof` because it does not know the PIN. The Subject device therefore emits a request with `proof = <placeholder: random-looking bytes>` OR leaves the `proof` field blank in the URL and includes only `requestSalt` + metadata. The Guardian device, on receiving the request, computes the real proof locally using its PIN, and the approval URL attaches the proof-derived AES key implicitly via encryption. There are two viable designs; we pick the simpler one:

- **Design A (chosen):** Subject sends `krypt://request?req=ID&app=PKG&salt=REQ_SALT&iat=TS`. No `proof` field. The approval URL is encrypted with an AES-256 key derived by the Guardian as `HKDF(PBKDF2(PIN, pubSalt | reqSalt, iter))`. The Subject device, on receiving the approval, derives the same AES key (it knows the Guardian's `pubSalt` from pairing, knows the request's `reqSalt` from having issued it, and derives `PBKDF2(PIN, ...)` using... wait, the Subject doesn't know the PIN).

- **Design B (actual chosen):** Two-stage KDF. A long-term shared secret `K_pair` is established at pairing time via an ephemeral Diffie-Hellman embedded in `krypt://pair` / `krypt://paired`. Both devices keep `K_pair` in local storage. Per-request symmetric key is `HKDF(K_pair, requestId | requestSalt)`. The Guardian's PIN validation is a separate step, local-only to the Guardian device. An approval is the Guardian's local "I verified the PIN for this request" state, encrypted under `HKDF(K_pair, requestId | requestSalt)`.

Design B is cleaner and matches modern E2E messaging patterns. **However**, the spec's wording of "proof = SHA256(PIN+salt)" suggests Design A. To remain faithful to the spec while getting acceptable security:

- **Final design (hybrid):** Pairing establishes `K_pair` (long-term shared secret, 256-bit, established from a pairing-time Diffie-Hellman). The request URL carries `req`, `app`, `salt`, `iat`. The approval URL carries `req`, `data` where `data = AES-256-GCM(K_req, grantPayload)` and `K_req = HKDF(K_pair, req || salt)`. The Guardian device gates *showing the PIN-prompt UI* on the PIN matching a local stored hash (`PBKDF2(PIN, pubSalt, iter) == storedHash`). The PIN never travels; the KDF+AES is for approval-payload confidentiality, not authentication.

This is slightly richer than the spec's naive `SHA256(PIN+salt)` proof, but it is the honestly-secure interpretation. The spec's threat model assumes an attacker can capture the URL; under Design A that attacker could brute-force the PIN offline, which is exactly the weakness FR-008 tries to mitigate. Under Design B/hybrid, the PIN brute-force attack simply doesn't exist in the URL capture space, because the PIN isn't in the URL's proof at all.

**Action.** `plan.md` flags this hybrid as the chosen interpretation. `/polaris.tasks` will include a subtask to confirm with the user that Design B/hybrid is acceptable before implementation. If the user insists on the literal spec (naive Design A), we degrade gracefully.

**Invariants.**
- `consumed = 0` rows are shown in the Locker overlay "pending" state.
- `consumed = 1` rows are retained for 24 hours (audit) then pruned.
- `expiresAt < now` rows are ignored during approval matching and pruned.

---

## Entity: `UnlockGrant`

**Purpose.** Records a successful unlock so the overlay knows when to show or not show. Written on approval consumption.

**Persistence.** Room `@Entity(tableName = "unlock_grants")`.

```
id              INTEGER PRIMARY KEY AUTOINCREMENT
requestId       TEXT    NOT NULL REFERENCES outstanding_requests(requestId)
targetPackage   TEXT    NOT NULL
grantedAt       INTEGER NOT NULL
expiresAt       INTEGER NOT NULL  -- typically grantedAt + defaultGrantDurationMs (15 min default)
```

**Invariants.**
- A grant is consumed by the Locker overlay: if `now < grant.expiresAt`, the overlay is not shown.
- Multiple grants for the same package can exist; the overlay checks `max(expiresAt) > now`.
- On process death, grants survive because they're persisted. On process restart, `LockerSessionStore` rebuilds its in-memory cache from `SELECT * FROM unlock_grants WHERE expiresAt > now`.

---

## Entity: `LockerSession` (in-memory)

**Purpose.** Fast-path cache for the Accessibility Service's hot loop. Reading from Room on every foreground event adds 2-10 ms which is significant in the <200 ms overlay-latency budget.

**Shape.** Kotlin `ConcurrentHashMap<String, Long>` mapping `packageName -> expiresAt`. Wrapped by `LockerSessionStore` with methods `isUnlockedNow(pkg): Boolean`, `recordGrant(pkg, expiresAt)`, `expireAll()`.

**Lifecycle.** Rebuilt at service-start from `UnlockGrant` table; updated on every new grant via Flow from the repository; expired entries removed by a 30-second timer.

---

## Settings (DataStore)

```
proto Settings {
  int32  kdf_iterations = 1;          // calibrated at onboarding
  int32  default_grant_minutes = 2;   // default 15
  bool   onboarding_complete = 3;
  int64  last_guardian_pair_at = 4;   // epoch ms
}
```

Accessed via `SettingsRepository` with `Flow<Settings>`.

---

## Entity Relationships

```
GuardianPairing (1) --- (*) OutstandingRequest
                                |
                                *
                        UnlockGrant (0..*)
```

- `GuardianPairing` is a single row; `OutstandingRequest`s reference the current pairing implicitly via `pubSalt` that was in use at issue time.
- `OutstandingRequest`s (1) optionally have (0..1 consumed) `UnlockGrant`s linked by `requestId`.
- `LockedApp` rows are logically orthogonal to the Guardian model; a locked app is just a package name.

---

## Data flows

### Flow 1: New install -> lock
`PackageReceiver` -> `LockedAppsRepository.lockNewlyInstalledApp(pkg, name)` -> `LockedAppDao.insert` -> `NotificationHelper.notifyAppLocked(pkg, name)`.

### Flow 2: Foreground event -> overlay decision
`AppLockerAccessibilityService.onAccessibilityEvent(ev)` -> extract `pkg = ev.packageName` -> (in memory) `if LockerSessionStore.isUnlockedNow(pkg) return` -> `LockedAppsRepository.checkIfAppIsLocked(pkg)` (from Flow-backed cache) -> `OverlayManager.show(pkg)` or `hide()`.

### Flow 3: Subject request
`LockerOverlayView.onAskGuardianClicked()` -> `UnlockRequestBuilder.build(pkg)` -> insert `OutstandingRequest` -> share URL intent.

### Flow 4: Guardian approval
`GuardianActivity.onCreate(deepLinkUri)` -> `UnlockRequestParser.parse(uri)` -> show PIN UI -> `ProofVerifier.verifyPin(pin, pubSalt)` -> on success `ApprovalLinkBuilder.build(request, K_pair)` -> share URL intent.

### Flow 5: Subject approval consumption
`GuardianActivity.onCreate(approveLinkUri)` -> `ApprovalLinkParser.parse(uri)` -> `ProofVerifier.matchOutstandingRequest(req)` -> `AesGcmCipher.decrypt(data, K_req)` -> insert `UnlockGrant` -> `LockerSessionStore.recordGrant` -> `OverlayManager.hide()`.

All five flows compose with suspend functions and `Flow<T>` where appropriate, dispatched on `Dispatchers.IO` for storage and `Dispatchers.Default` for KDF work; the Accessibility hot-path avoids coroutine launching for the cache-hit case (pure synchronous map read).
