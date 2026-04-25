---
work_package_id: WP04
lane: planned
dependencies: [WP02]
base_branch: main
subtasks: [T019, T020, T021, T022, T023, T024]
test_status: required
test_file: tests/002-manual-lock-and-permission-clarity/WP04-home-screen-manual-lock.spec.js
domain: frontend-craft
---

# WP04 - Home Screen + manual lock toggle

## Objective

Replace the current post-onboarding placeholder Home with a searchable list of installed apps. Each row has a one-way Material toggle: tapping an unlocked toggle locks the app instantly; tapping a locked toggle does NOT unlock --- it dispatches a `krypt://request` URL via the system Share sheet.

## Context

- **Spec:** FR-030 through FR-035, SC-011, SC-012, SC-013, SC-015. US-4, US-5, US-6, US-7.
- **Depends on WP02:** consumes `InstalledAppsRepository`, `AppIconCache`, `InstalledAppMeta`.
- **Reuses:** `LockedAppsRepository.allLockedFlow()` (feature 001 WP06), `LockedAppsRepository.lock(pkg)`, `UnlockRequestBuilder.build(...)` (feature 001 WP20 amendment 1), `MasterKeyStore` (feature 001 WP19).
- **Strings:** Add new keys for search hint, locked label, locked-tap-explanation snackbar.

## Subtasks

### T019 --- `HomeViewModel`

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/home/HomeViewModel.kt`

**Implementation:**
1. `@HiltViewModel class HomeViewModel @Inject constructor(private val installedRepo: InstalledAppsRepository, private val lockedRepo: LockedAppsRepository, private val unlockRequestBuilder: UnlockRequestBuilder, private val masterKeyStore: MasterKeyStore) : ViewModel()`.
2. Internal `private val _query = MutableStateFlow("")`.
3. Internal `private val _allInstalled = MutableStateFlow<List<InstalledAppMeta>>(emptyList())`. On init, `viewModelScope.launch { _allInstalled.value = installedRepo.allInstalled() }`. Re-fetch on `refresh()` (called from screen `onResume`).
4. Public state:
   ```kotlin
   data class HomeUiState(val rows: List<InstalledAppRowState>, val query: String)
   data class InstalledAppRowState(val packageName: String, val displayName: String, val isLocked: Boolean)
   ```
   `val state: StateFlow<HomeUiState>` is a `combine(_allInstalled, lockedRepo.allLockedFlow(), _query) { all, locked, q -> ... }` that joins the lists, filters by query (case-insensitive substring on `displayName`), and maps to `InstalledAppRowState`.
5. `fun setQuery(q: String) { _query.value = q }`.
6. `fun onToggle(row: InstalledAppRowState)`:
   - If `!row.isLocked` → `viewModelScope.launch { lockedRepo.lock(row.packageName, source = MANUAL) }`. The `LockSource.MANUAL` enum value already exists (feature 001 WP06); if it does not exist for some reason, add it.
   - If `row.isLocked` → `viewModelScope.launch { dispatchUnlockRequest(row) }`. Implementation in T022.
7. Public `val shareIntents: Channel<Intent> = Channel(Channel.BUFFERED)` consumed by the screen via `LaunchedEffect`.

### T020 --- `InstalledAppRow` composable

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/home/InstalledAppRow.kt`

**Implementation:**
1. `@Composable fun InstalledAppRow(state: InstalledAppRowState, iconCache: AppIconCache, onToggle: () -> Unit, modifier: Modifier = Modifier)`.
2. Layout: a `Row` with an `Image` (icon --- 48 dp), `Spacer`, `Column { Text(displayName); if (state.isLocked) { Text("Locked", style = labelSmall, color = success) } }`, `Spacer(Modifier.weight(1f))`, `Switch(checked = state.isLocked, onCheckedChange = { onToggle() })`.
3. Icon resolution: `val icon by produceState<Drawable?>(initialValue = null, state.packageName) { value = iconCache.loadAsync(state.packageName) }`. Render `icon?.toBitmap()?.asImageBitmap()` inside the Image, with a placeholder while null.
4. Important: `Switch.onCheckedChange` ignores the OS's reported new value --- we ALWAYS call `onToggle()` and let the view model decide. This is essential to keep the toggle from "flickering" when an unlock attempt is rejected.

### T021 --- `HomeScreen` composable

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/home/HomeScreen.kt`

**Implementation:**
1. `@Composable fun HomeScreen(viewModel: HomeViewModel = hiltViewModel(), iconCache: AppIconCache)`.
2. Layout:
   - `Column { ... }` with a `Surface` header containing an `OutlinedTextField` for the search query (placeholder = `R.string.home_search_hint`).
   - `LazyColumn(modifier = Modifier.fillMaxSize())` with `items(state.rows, key = { it.packageName })` rendering `InstalledAppRow`.
3. Wire `LaunchedEffect(Unit) { viewModel.shareIntents.consumeEach { intent -> context.startActivity(Intent.createChooser(intent, context.getString(R.string.home_share_chooser_title))) } }`.
4. `LifecycleEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }` so newly installed apps appear without manual reload.
5. Empty state: if `state.rows.isEmpty()` and `query.isBlank()`, show a centered "Loading apps..." placeholder. If `query.isNotBlank()`, show "No apps match \"$query\"".
6. Add new `strings.xml` keys: `home_search_hint`, `home_share_chooser_title`, `home_label_locked`, `home_empty_loading`, `home_empty_no_match`.

### T022 --- One-way toggle logic + `dispatchUnlockRequest`

**Files to modify:**
- `app/src/main/java/com/krypt/app/ui/home/HomeViewModel.kt` (extend with the unlock-request method).

**Implementation:**
1. `private suspend fun dispatchUnlockRequest(row: InstalledAppRowState)`:
   - Read `salt = masterKeyStore.loadSalt()` and `pinProof = masterKeyStore.loadPinProof()`. If either is null (Subject not yet configured) → emit a one-shot user-error event (separate `Channel<UserMessage>`); do NOT crash.
   - Generate a fresh `requestId = UUID.randomUUID()`.
   - Call `unlockRequestBuilder.build(requestId, targetPackage = row.packageName, setupSalt = salt, pinProof = pinProof, ttlSeconds = 300)` → returns the `(url, request)` pair.
   - `val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url); putExtra(Intent.EXTRA_SUBJECT, "Krypt unlock request: ${row.displayName}") }`.
   - `shareIntents.send(intent)`.
2. The toggle in the row stays bound to `state.isLocked` from the StateFlow --- since we never write to `LockedAppsRepository` from this branch, the StateFlow does not re-emit, the `Switch` keeps its `checked = true`. No flicker. This is the entire mechanism behind FR-033.
3. Keep this method `suspend` so the test in T023 can use Turbine to assert the `shareIntents` emission.

### T023 --- `HomeViewModelTest`

**Files to create:**
- `app/src/test/java/com/krypt/app/ui/home/HomeViewModelTest.kt`

**Coverage:**
1. MockK `InstalledAppsRepository`, `LockedAppsRepository`, `UnlockRequestBuilder`, `MasterKeyStore`.
2. Initial state: install repo returns `[A, B, C]`, locked repo returns `{B}` → `state.rows == [A(unlocked), B(locked), C(unlocked)]`.
3. `setQuery("a")` → only rows whose displayName contains "a" remain, case-insensitive.
4. `onToggle(unlocked row)` → exactly one call to `lockedRepo.lock(packageName = "A", source = MANUAL)`. No `unlockRequestBuilder` interaction. State eventually shows `A` as locked.
5. `onToggle(locked row)` → exactly one call to `unlockRequestBuilder.build(...)`. `shareIntents` emits a single `Intent` whose `EXTRA_TEXT` is the returned URL. **`lockedRepo.unlock(...)` is never called**. State remains unchanged (B still locked).
6. `onToggle(locked row)` when `masterKeyStore.loadSalt() == null` → emits a `UserMessage.MasterKeyMissing` event; does NOT crash; does NOT call `unlockRequestBuilder.build`.

### T024 --- Compose UI test for `HomeScreen`

**Files to create:**
- `app/src/test/java/com/krypt/app/ui/home/HomeScreenTest.kt`

**Coverage:**
1. Inject a fake view model whose `state` flow can be driven from the test. Provide `[A(unlocked), B(locked), C(unlocked)]`.
2. Assert all three row labels are displayed; assert the second row's `Switch` is checked.
3. Type "C" into the search field → assert only row "C" displays.
4. Click row A's switch → assert the fake view model received `onToggle(A)`.
5. Click row B's switch → assert `onToggle(B)` was called AND that `state` did NOT change (B's switch is still checked). Use `composeTestRule.mainClock.advanceTimeBy(50)` to give Compose a frame to recompose; then assert.
6. Drive a single `Intent` into the `shareIntents` channel from the test; assert that `context.startActivity` was called (intercept via a test `Activity.startActivity` shadow or a fake `Context` if possible).

## Definition of Done

- [ ] `HomeScreen` renders a sorted, searchable list of installed apps with correct lock state.
- [ ] Tapping an unlocked toggle persists `LockedAppsRepository.lock(pkg, source = MANUAL)` and the row re-emits as locked within one frame.
- [ ] Tapping a locked toggle does NOT change state and does NOT call `LockedAppsRepository.unlock`. A `krypt://request?...` `ACTION_SEND` intent is dispatched via the share sheet.
- [ ] Search filtering is case-insensitive and runs in <100 ms for 200 apps (informal benchmark; measured under Robolectric is fine).
- [ ] No new permission added to `AndroidManifest.xml`.
- [ ] All unit + Compose tests green.

## Risks and Edge cases

- **Toggle flicker.** If we accidentally let `Switch` mutate its own `checked` state via the OS-reported value, the user will see a half-second of OFF before our state restores it to ON. Mitigation: always pass `checked = state.isLocked` as the source of truth and have `onCheckedChange` ignore its argument.
- **`MasterKeyStore` not configured.** If a user clears app data and reaches Home before re-setup (currently MainActivity routing should prevent this), the `dispatchUnlockRequest` path must degrade gracefully. Show a snackbar and abort.
- **App uninstalled mid-render.** `pm.getApplicationIcon` throws `NameNotFoundException`. T009's `AppIconCache` already handles this; the row's text will still render and the row will disappear after next `refresh()`.
- **Search regex DoS.** The query is used in plain `String.contains(..., ignoreCase = true)`, not regex. Safe.
- **Large lists + LazyColumn keys.** Use `key = { it.packageName }` so reordering on filter is O(visible items), not O(all items).

## Reviewer guidance

- Build and run on a device with 100+ installed apps. Verify scroll fluidity and that toggle taps respond within one frame.
- Confirm via `adb shell dumpsys clipboard` (or just by sharing into Notes) that the URL emitted on a locked-toggle tap parses through `UnlockRequestParser` cleanly --- the most important contract this WP fulfils.
- Implement command: `polaris implement WP04 --base WP02`.
