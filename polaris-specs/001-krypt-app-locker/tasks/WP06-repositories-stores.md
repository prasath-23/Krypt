---
work_package_id: WP06
lane: "for_review"
dependencies: [WP05]
base_branch: 001-krypt-app-locker-WP05
base_commit: f8ad4e3fe0a3c4c11b26ca2e5dd146cafaa27f52
created_at: '2026-04-24T07:18:32.180712+00:00'
subtasks: [T027, T028, T029, T030, T031]
test_status: required
test_file: tests/e2e/WP06-wp06-repositories-stores.spec.js
agent: "claude"
---

# WP06 - Data layer: repositories, in-memory stores, DataStore settings

## Objective

Wrap Room DAOs in repository interfaces (satisfying FR-016's "mock seam" requirement), add in-memory caches for hot-path reads (Accessibility Service), EncryptedSharedPreferences-backed `KPairStore`, and DataStore-backed settings (calibrated KDF iteration count, onboarding state).

## Context

- **Spec FR-016:** mock seam for locked-apps DB.
- **Data model:** `data-model.md` sections "Persistence layers", "Repository seam", "LockerSession" in-memory cache.
- **Dependency on WP05:** DAOs + entity classes exist.
- **WP04 declared interface:** `KPairStore` (EncryptedSharedPreferences impl is here).

## Subtasks

### T027 - LockedAppsRepository (interface + Room impl + Fake)

**Purpose.** FR-016 seam. Fake used by `AppLockerAccessibilityService` tests (WP10) and `PackageReceiver` tests (WP08) to avoid spinning up Room.

**Files to create:**
- `app/src/main/java/com/krypt/app/data/LockedApp.kt` (domain type, distinct from `LockedAppEntity`)
- `app/src/main/java/com/krypt/app/data/LockedAppsRepository.kt` (interface)
- `app/src/main/java/com/krypt/app/data/RoomLockedAppsRepository.kt` (Room impl)
- `app/src/test/java/com/krypt/app/data/FakeLockedAppsRepository.kt`

**Implementation steps:**
1. `LockedApp` domain class mirrors `LockedAppEntity` shape 1-to-1 but stays pure Kotlin; mapper extensions `LockedAppEntity.toDomain()` / `LockedApp.toEntity()`.
2. Interface:
   ```
   interface LockedAppsRepository {
       suspend fun checkIfAppIsLocked(pkg: String): Boolean
       suspend fun lockNewlyInstalledApp(pkg: String, displayName: String)
       fun observeLockedApps(): Flow<List<LockedApp>>
       suspend fun unlockAppUntil(pkg: String, expiresAtMs: Long)
       suspend fun findByPackage(pkg: String): LockedApp?
   }
   ```
3. `RoomLockedAppsRepository @Inject constructor(private val dao: LockedAppDao, private val clock: Clock)` delegates to `dao` + maps.
4. `FakeLockedAppsRepository` (test): `@Volatile var apps: MutableList<LockedApp>` + `MutableStateFlow<List<LockedApp>>`.
5. Hilt binding: declare `@Binds` in a `data/DataModule.kt` (Singleton scope).

**Validation.** Unit test of `RoomLockedAppsRepository` using in-memory Room (via AndroidX test rule).

### T028 - GuardianRepository + KPairStore (EncryptedSharedPreferences)

**Purpose.** Single source of truth for pairing state + encrypted K_pair blob.

**Files to create:**
- `app/src/main/java/com/krypt/app/data/GuardianRepository.kt` (interface + RoomGuardianRepository impl)
- `app/src/main/java/com/krypt/app/crypto/EncryptedKPairStore.kt`

**Implementation steps:**
1. `GuardianRepository`:
   - `suspend fun getPairing(): GuardianPairing?`
   - `fun observePairing(): Flow<GuardianPairing?>`
   - `suspend fun savePairing(p: GuardianPairing)`
   - `suspend fun clearPairing()`
2. `GuardianPairing` domain type (role, remoteDisplayName, pubSalt, kdfIterations, pairedAt). Not to be confused with `GuardianPairingEntity` (Room).
3. `EncryptedKPairStore`:
   - Constructor takes `@ApplicationContext ctx`.
   - Uses `EncryptedSharedPreferences.create(ctx, "krypt_kpair", MasterKey.Builder(ctx).setKeyScheme(AES256_GCM).build(), AES256_SIV, AES256_GCM)`.
   - Stores `kPair` as base64 string under key `"k_pair"`.
   - `suspend fun save(kPair: ByteArray)` on `Dispatchers.IO`.
   - `suspend fun load(): ByteArray?` (nullable if unpaired).
   - `suspend fun clear()` removes the key.
   - `fun isPaired(): Boolean` synchronous (reads `contains("k_pair")`).
4. Hilt binding `KPairStore -> EncryptedKPairStore`.

**Validation.** Save -> process-restart simulation (clear process-level cache) -> load returns same bytes. ClearKey -> load returns null.

### T029 - OutstandingRequestRepository + UnlockGrantRepository

**Purpose.** Wrap OutstandingRequest and UnlockGrant DAOs.

**Files to create:**
- `app/src/main/java/com/krypt/app/data/OutstandingRequest.kt` (domain)
- `app/src/main/java/com/krypt/app/data/OutstandingRequestRepository.kt`
- `app/src/main/java/com/krypt/app/data/UnlockGrant.kt` (domain)
- `app/src/main/java/com/krypt/app/data/UnlockGrantRepository.kt`

**Implementation steps:**
1. Domain types mirror entity shapes.
2. `OutstandingRequestRepository`:
   - `suspend fun insert(req: OutstandingRequest)`
   - `suspend fun findById(id: UUID): OutstandingRequest?`
   - `fun observeOpen(now: Long): Flow<List<OutstandingRequest>>`
   - `suspend fun markConsumedIfOpen(id: UUID, now: Long): Boolean` (wraps DAO return int-to-boolean)
   - `suspend fun pruneOld(now: Long, pruneBeforeMs: Long)`
3. `UnlockGrantRepository`:
   - `suspend fun insert(grant: UnlockGrant): Long`
   - `suspend fun activeGrantForPackage(pkg: String, now: Long): UnlockGrant?`
   - `fun observeActive(now: Long): Flow<List<UnlockGrant>>`
   - `suspend fun pruneExpired(now: Long)`
4. Both Room-backed + Fake impls under test.

**Validation.** Repo-level unit tests with in-memory Room.

### T030 - LockerSessionStore (in-memory, thread-safe)

**Purpose.** Hot-path cache avoiding a DB read on every Accessibility event.

**Files to create:**
- `app/src/main/java/com/krypt/app/data/LockerSessionStore.kt`

**Implementation steps:**
1. `@Singleton class LockerSessionStore @Inject constructor(private val clock: Clock)`.
2. Internal `ConcurrentHashMap<String, Long>` mapping packageName -> expiresAt (ms).
3. `fun isUnlockedNow(pkg: String): Boolean = (cache[pkg] ?: 0L) > clock.nowMs()`.
4. `fun recordGrant(pkg: String, expiresAt: Long) { cache[pkg] = expiresAt; scheduleExpiryWipe() }`.
5. `fun expireAll()` for PIN-rotation wipe.
6. `suspend fun rebuildFrom(repo: UnlockGrantRepository)` - called at `KryptApplication.onCreate`; loads all grants with `expiresAt > now` and populates cache.
7. Scheduled expiry: use a coroutine `GlobalScope` (or app-scope from Hilt) with `delay(nextExpiryMs - now)` + `cache.entries.removeIf { it.value <= now }`. Single re-scheduled job.

**Validation.**
- Unit test: seed grants, call `isUnlockedNow`, verify before/after expiry.
- Concurrency test: 1000 parallel `recordGrant` + `isUnlockedNow` - no `ConcurrentModificationException`.

### T031 - SettingsRepository (Proto DataStore)

**Purpose.** Scalar app settings: calibrated KDF iterations, default grant duration, onboarding-complete flag.

**Files to create:**
- `app/src/main/java/com/krypt/app/data/settings/KryptSettings.kt` (protobuf definition)
- `app/src/main/proto/krypt_settings.proto` (wire definition)
- `app/src/main/java/com/krypt/app/data/settings/SettingsSerializer.kt`
- `app/src/main/java/com/krypt/app/data/settings/SettingsRepository.kt`
- `app/src/test/java/com/krypt/app/data/settings/SettingsRepositoryTest.kt`

**Implementation steps:**
1. Add `protobuf-lite` + `datastore-core` to `app/build.gradle.kts` (first-party AndroidX + first-party Google protobuf-lite runtime).
2. Define `krypt_settings.proto`: `int32 kdf_iterations`, `int32 default_grant_minutes`, `bool onboarding_complete`, `int64 last_guardian_pair_at`.
3. `SettingsSerializer` extends `Serializer<KryptSettings>` with default value.
4. `SettingsRepository`:
   - `@Inject constructor(private val dataStore: DataStore<KryptSettings>)`.
   - `val settings: Flow<KryptSettings> = dataStore.data`.
   - `suspend fun setKdfIterations(n: Int)` via `updateData { it.copy(kdfIterations = n) }`.
   - `suspend fun setDefaultGrantMinutes(n: Int)`.
   - `suspend fun setOnboardingComplete(v: Boolean)`.
5. Hilt module: `@Provides @Singleton fun dataStore(...)` wiring to `dataStore(...)` with the serializer.

**Validation.** DataStore round-trip: update -> `settings.first()` -> verify.

## Test Strategy

- **Unit (JVM):** Fakes for each repo; `SettingsRepositoryTest` uses a temporary DataStore file.
- **Instrumented (androidTest):** Repository integration tests against in-memory Room, EncryptedSharedPreferences tests use real Keystore (requires device/emulator).
- **Concurrency:** `LockerSessionStore` concurrency test (T030).

## Definition of Done

- [ ] All repository interfaces compile and have both Room (or real-backing-store) impls AND Fake impls.
- [ ] `FakeLockedAppsRepository` is used by at least one test in WP03 (to validate it's a working test double).
- [ ] EncryptedSharedPreferences-backed `KPairStore` round-trips 32-byte keys across process restart (androidTest).
- [ ] DataStore serializer handles a fresh install (empty file) by returning default `KryptSettings`.
- [ ] `LockerSessionStore` concurrency test passes 100 consecutive runs.
- [ ] Hilt `DataModule` compiles and binds all interfaces to their impls.

## Risks + Edge cases

- **Protobuf vs Preferences DataStore.** Proto is stricter and schema-evolvable; Preferences is simpler but stringly-typed. Plan specifies Proto. If Kotlin Gradle plugin + proto-lite + DataStore integration is thorny, fallback to Preferences DataStore for this WP with a documented TODO to migrate (low cost, single-digit LOC).
- **EncryptedSharedPreferences on API 29.** AndroidX security-crypto 1.1-alpha06 requires minSdk 23; works on 29. But Keystore deterministic backups can be flaky on OEM-modified ROMs; wrap all calls in try/catch and log via a `Timber` tree (already first-party / internal-only).
- **K_pair in memory.** `EncryptedKPairStore.load()` returns `ByteArray`; the decrypted bytes live in heap indefinitely if callers hold the reference. Downstream consumers (ApprovalConsumer, PairedReplyVerifier) should copy into a local and zero after use where possible. Document in KDoc.
- **LockerSessionStore ↔ Accessibility event hot path.** Reading from ConcurrentHashMap is ~100 ns; still much faster than a Room read. Validate with a microbenchmark (optional, noted as a soft goal).

## Reviewer Guidance

- Confirm `FakeLockedAppsRepository` lives in `src/test/` (and `src/androidTest/`? pick one) and NOT in `src/main/`.
- Check that no impl silently swallows exceptions from EncryptedSharedPreferences/Keystore - failures should surface so onboarding can re-prompt.
- Verify `KPairStore.isPaired()` is synchronous - it's called from UI gates that cannot suspend.
- Run `./gradlew :app:connectedDebugAndroidTest --tests "com.krypt.app.data.*"` and paste output.

## Next command

```
polaris implement WP06 --base WP05
```

## Activity Log

- 2026-04-24T07:21:48Z – claude – lane=doing – repos
- 2026-04-24T07:21:56Z – claude – lane=testing – tested
- 2026-04-24T07:22:06Z – claude – lane=for_review – WP06 done
