---
work_package_id: WP02
lane: "doing"
dependencies: []
base_branch: main
base_commit: e8cd6edf7902f13052760e6cb300c49c0a4c4acf
created_at: '2026-04-25T06:05:35.753768+00:00'
subtasks: [T007, T008, T009, T010, T011]
test_status: required
test_file: tests/002-manual-lock-and-permission-clarity/WP02-installed-apps-repository.spec.js
domain: backend-logic
shell_pid: "18456"
---

# WP02 - Installed apps repository

## Objective

Build the data source for the new Home Screen --- a sorted, filtered list of installed non-system apps with deferred icon loading. WP04 consumes this; WP01 is independent of this WP and can land in parallel.

## Context

- **Spec:** FR-030, FR-031, FR-036, SC-013, SC-015.
- **Feature 001 reuse:** The existing `PackageReceiver` (WP08 of feature 001) auto-locks new installs after applying a "system app filter". The same filter logic must be reused so the Home Screen list matches what the auto-locker would consider lockable.
- **Out of scope:** Real-time list updates as new apps install/uninstall (that flow already routes through the existing receiver and updates the Locked-apps Room table; the Home list re-queries on resume --- good enough for v1).
- **Performance:** A device with 200 installed apps must paint the first screen in <1.5 s. Icons are the bottleneck; we lazy-load them per row plus an LRU cache.

## Subtasks

### T007 --- `InstalledAppMeta` data class

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/home/InstalledAppMeta.kt`

**Implementation:**
1. `data class InstalledAppMeta(val packageName: String, val displayName: String)`.
2. Keep this minimal --- icons are fetched on demand via `AppIconCache` (T009), not embedded in the meta.

### T008 --- `InstalledAppsRepository` interface + Android impl

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/home/InstalledAppsRepository.kt`
- `app/src/main/java/com/krypt/app/ui/home/AndroidInstalledAppsRepository.kt`

**Implementation:**
1. `interface InstalledAppsRepository { suspend fun allInstalled(): List<InstalledAppMeta> }`.
2. `class AndroidInstalledAppsRepository @Inject constructor(@ApplicationContext private val context: Context) : InstalledAppsRepository`:
   - `override suspend fun allInstalled(): List<InstalledAppMeta> = withContext(Dispatchers.IO) { ... }`.
   - `pm.getInstalledApplications(0)` → filter `(it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0`.
   - Reuse the curated allowlist string-set from feature 001 `PackageReceiver` (extract to a shared `SystemPackageFilter` if not already shared). Reject Krypt's own package, the system launcher, IME apps, etc.
   - Map to `InstalledAppMeta(packageName = info.packageName, displayName = info.loadLabel(pm).toString())`.
   - Sort by `displayName` using a `Collator` set to the device's primary locale so non-Latin app names sort correctly.
3. Hilt binding deferred to T010.

### T009 --- `AppIconCache` and deferred loader

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/home/AppIconCache.kt`

**Implementation:**
1. `@Singleton class AppIconCache @Inject constructor(@ApplicationContext private val context: Context)`:
   - Internal `LruCache<String, Drawable>` sized so total bitmap bytes ≤ `MAX_BYTES = 16 * 1024 * 1024`.
   - `fun load(packageName: String): Drawable` --- synchronous-on-IO call: cache hit → return; miss → `pm.getApplicationIcon(packageName)`, store, return.
2. Add `suspend fun loadAsync(pkg: String): Drawable = withContext(Dispatchers.IO) { load(pkg) }` for Compose `produceState`.
3. Handle `PackageManager.NameNotFoundException` (uninstall mid-load) → return a generic neutral placeholder drawable (e.g., `R.drawable.ic_default_app`).

### T010 --- Hilt bindings

**Files to modify:**
- `app/src/main/java/com/krypt/app/di/PermissionModule.kt` (or new `HomeModule.kt`).

**Implementation:**
1. `@Provides @Singleton fun installedAppsRepository(impl: AndroidInstalledAppsRepository): InstalledAppsRepository = impl` --- or use `@Binds` interface-bind in an `abstract class HomeModuleBindings`.
2. `AppIconCache` is `@Singleton` and constructor-injected --- no provides needed beyond Hilt's automatic detection.

### T011 --- Unit tests

**Files to create:**
- `app/src/test/java/com/krypt/app/ui/home/AndroidInstalledAppsRepositoryTest.kt` --- Robolectric, install fake `PackageInfo` entries via `Shadows.shadowOf(packageManager).addPackage(...)`, verify FLAG_SYSTEM filter and Collator sort.
- `app/src/test/java/com/krypt/app/ui/home/AppIconCacheTest.kt` --- verify cache hit returns same instance, miss calls `pm.getApplicationIcon`, eviction kicks in beyond `MAX_BYTES` (use small synthetic drawables).

## Definition of Done

- [ ] `InstalledAppsRepository.allInstalled()` returns sorted, filtered list on Robolectric.
- [ ] `AppIconCache` evicts at the configured byte budget and never crashes on `NameNotFoundException`.
- [ ] No new permission added to `AndroidManifest.xml` (the existing `QUERY_ALL_PACKAGES` from feature 001 remains the source).
- [ ] `./gradlew :app:testDebugUnitTest --tests "com.krypt.app.ui.home.*"` is green.

## Risks and Edge cases

- **`QUERY_ALL_PACKAGES` Play Store policy.** Already declared and justified in feature 001 (auto-lock requires it). No new declaration; no policy implication.
- **Icon Drawable thread safety.** Drawables are not safe to share across threads when mutated. Treat the cache as read-only after load; do NOT call `setColorFilter` etc. on cached drawables.
- **Locale changes mid-session.** If the user changes device locale, sort order is stale until the activity is recreated. Acceptable for v1; the activity will recreate on locale change anyway.
- **Compose recomposition cost** --- addressed in WP04 via `LazyColumn` + stable keys. Not in this WP.

## Reviewer guidance

- Confirm that `AndroidInstalledAppsRepository` does NOT call any UI APIs (it's pure data).
- Confirm `AppIconCache.MAX_BYTES = 16 MB` matches the plan.md figure.
- Implement command: `polaris implement WP02`.
