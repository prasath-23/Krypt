---
work_package_id: WP15
lane: "for_review"
dependencies: [WP10, WP14]
base_branch: 001-krypt-app-locker-WP14
base_commit: a44e20d3cf78b75a892e1c37f3f5a3ffbe4964d7
created_at: '2026-04-24T07:46:20.164185+00:00'
subtasks: [T071, T072, T073, T074, T075]
test_status: required
test_file: tests/e2e/WP15-wp15-approval-consumption.spec.js
agent: "claude"
---

# WP15 - Approval consumption + overlay dismiss integration

## Objective

Close the loop. On the Subject device, receiving a `krypt://approve` URL decrypts the payload, verifies it against an outstanding request, writes an `UnlockGrant`, updates the in-memory `LockerSessionStore`, and dismisses the Locker overlay for the target package. Covers spec US-5, FR-013, FR-015.

## Context

- **Spec US-5, FR-013, FR-015.**
- **Contracts:** `contracts/approve.md` (approval URL format + consumption pipeline).
- **Dependencies:** WP10 (AccessibilityService + OverlayManager.hide), WP14 (GuardianActivity dispatch for `krypt://approve`).
- **Crypto + pipeline** already in WP03's `ApprovalConsumer`.

## Subtasks

### T071 - GuardianApprovalConsumeScreen

**Files to create:**
- `app/src/main/java/com/krypt/app/ui/guardian/GuardianApprovalConsumeScreen.kt`
- `app/src/main/java/com/krypt/app/ui/guardian/GuardianApprovalViewModel.kt`

**Implementation steps:**

1. `@HiltViewModel class GuardianApprovalViewModel @Inject constructor(private val consumer: ApprovalConsumer, private val overlayManager: OverlayManager, private val sessionStore: LockerSessionStore, private val lockedAppsRepo: LockedAppsRepository) : ViewModel()`:
   - `suspend fun consume(uri: Uri): Result<ApprovalOutcome, ApprovalError>` calls `consumer.consume(uri, now = clock.nowMs())`.
   - Does NOT write grants directly; `ApprovalConsumer` does via injected repositories.
2. `@Composable fun GuardianApprovalConsumeScreen(uri: Uri, viewModel: ..., onDone: () -> Unit)`:
   - On entry: `LaunchedEffect(uri) { viewModel.consume(uri) }`.
   - State machine:
     - `Loading` -> CircularProgressIndicator + "Decrypting approval...".
     - `Success(outcome)` -> green check card "Unlocked {targetAppName} until {time}", auto-launch target app + `onDone()` after 3 seconds.
     - `Error(e)` -> red card with message from `contracts/approve.md` table.

**Validation.** UI test fake-consumer branches.

### T072 - ApprovalConsumer transactional grant insertion

**Files to modify:**
- `app/src/main/java/com/krypt/app/deeplink/ApprovalConsumer.kt` (from WP03)

**Implementation steps:**

1. Currently `ApprovalConsumer` (WP03) outlines steps 1-10; step 8 needs to be finalised as a single Room transaction:
   ```
   suspend fun consumeInTransaction(requestId: UUID, targetPackage: String, expiresAtMs: Long): ConsumeTxResult = db.withTransaction {
     val consumed = outstandingReqDao.markConsumedIfOpen(requestId.toString(), now)
     if (consumed == 0) return@withTransaction ConsumeTxResult.AlreadyConsumed
     unlockGrantDao.insert(UnlockGrantEntity(requestId = requestId.toString(), targetPackage = targetPackage, grantedAt = now, expiresAt = expiresAtMs))
     ConsumeTxResult.Granted
   }
   ```
2. `ApprovalConsumer` step 8 substitutes the atomic-transaction call; step 9 (sessionStore update) and step 10 (return ApprovalOutcome) remain in the outer consume() method.
3. Room-level dependency: `@Database(... )` needs `runInTransaction` support via `db.withTransaction { ... }` from `androidx.room:room-ktx`.

**Validation.** Race test (re-run from WP05's T026): concurrent approvals -> exactly one Granted + one AlreadyConsumed.

### T073 - OverlayManager.hide() on approval success

**Files to modify:**
- `app/src/main/java/com/krypt/app/ui/guardian/GuardianApprovalViewModel.kt` (T071)
- Possibly `app/src/main/java/com/krypt/app/service/AppLockerAccessibilityService.kt` (subscribe to session store changes)

**Implementation steps:**

1. In `GuardianApprovalViewModel.consume`, on success:
   ```
   sessionStore.recordGrant(outcome.targetPackage, outcome.grantExpiresAt)
   overlayManager.hide()  // synchronous, no-op if not showing
   ```
2. Alternative (cleaner separation): `AppLockerAccessibilityService` already observes `lockedAppsRepo.observeLockedApps()`. Consider also observing `unlockGrantRepo.observeActive()` as a Flow; when a new grant arrives for `currentPackage`, hide the overlay.
3. Chosen: direct call in T073 to keep the "consume -> dismiss" latency tight (Service's Flow collector has a ~5-50 ms debounce).

**Validation.** Covered by T075.

### T074 - Auto-finish + target-app relaunch

**Implementation steps (in GuardianApprovalConsumeScreen):**

1. On success state, after 3 seconds:
   ```
   LaunchedEffect(outcome) {
     delay(3000)
     val launchIntent = pm.getLaunchIntentForPackage(outcome.targetPackage)
     if (launchIntent != null) {
       launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
       context.startActivity(launchIntent)
     }
     (context as Activity).finish()
   }
   ```
2. User can tap "Open now" button to skip the 3-second delay; or "Close" to avoid relaunching.

**Validation.** Manual walkthrough US-5.

### T075 - ApprovalConsumptionIntegrationTest (androidTest)

**Files to create:**
- `app/src/androidTest/java/com/krypt/app/ui/guardian/ApprovalConsumptionIntegrationTest.kt`

**Implementation steps:**

1. Setup:
   - HiltAndroidRule with real repository + in-memory Room + real `ApprovalConsumer`.
   - Seed `OutstandingRequest(requestId=UUID_FIXED, targetPackage="com.example.target", salt=fixedBytes, consumed=0)`.
   - Seed `KPairStore` with a known 32-byte key.
   - Build a valid `krypt://approve` URL using `ApprovalLinkBuilder` with those same inputs.
2. Test `validApprovalConsumesAndGrants`:
   - Launch GuardianActivity with the approval URL via `Intent(ACTION_VIEW)`.
   - Await success UI.
   - Assert: `unlockGrantRepo.activeGrantForPackage("com.example.target", now)` is non-null.
   - Assert: `outstandingReqDao.findById(...).consumed == 1`.
   - Assert: `sessionStore.isUnlockedNow("com.example.target") == true`.
3. Test `replayProducesUnmatchedRequest`:
   - Re-launch same URL.
   - Assert: error UI shown with "approval doesn't match any pending requests".
4. Test `tamperedCiphertextFails`:
   - Flip a bit in the `data=` parameter.
   - Assert: `CipherDecryptFailed` error UI.

**Validation.** 3/3 green on connected device.

## Test Strategy

- **Unit:** ApprovalConsumer tests already in WP03; T072 adds a transactional-pathway unit test.
- **Instrumented:** T075 end-to-end.
- **Manual:** Quickstart US-5.

## Definition of Done

- [ ] GuardianApprovalConsumeScreen handles all nine ApprovalError states (per contracts/approve.md) with distinct UI.
- [ ] Successful consume atomically marks request consumed + inserts UnlockGrant (race test passes).
- [ ] LockerSessionStore updated within 10 ms of success.
- [ ] OverlayManager.hide() called; verified by manual test.
- [ ] Target-app relaunch happens after 3 s (configurable) and user can skip.
- [ ] Integration test 3/3 on API 33+ device.

## Risks + Edge cases

- **Replay of stored approval URL after process restart.** `UnlockGrant` was inserted but `outstandingRequest.consumed=1` - replay returns `UnmatchedRequest` correctly.
- **Clock skew between issue and consume.** Grant `expiresAt = now + durMin * 60_000L`; `now` is Subject's clock, not Guardian's. If Subject's clock is behind, the grant might be shorter than intended. Accept within reason.
- **Relaunch-target-app fails.** If `getLaunchIntentForPackage` returns null (some system apps), show "Unlocked - open the app manually" without attempting launch.
- **Multiple approvals for same request.** `markConsumedIfOpen` returns 0 on the second; that's the existing race protection.
- **User backgrounds GuardianActivity before success UI.** `viewModelScope` cancels coroutines on ViewModel death. If the transaction already committed, grant is persisted and next-time overlay-check will honour it. If transaction was in-flight and cancelled, grant is not persisted - user will get retry UX on next approval tap.

## Reviewer Guidance

- Trace the atomic transaction: `db.withTransaction { dao.markConsumedIfOpen; dao.insert }` - confirm both in the same `@Transaction` method or wrapped in `withTransaction`.
- Verify UI shows `contentDescription` for success/error icons (a11y).
- Check that the 3-second auto-relaunch can be cancelled by user navigation (Activity finish check in the `LaunchedEffect`).
- Run integration test on a physical device; paste output + video.

## Next command

```
polaris implement WP15 --base WP14
```

## Activity Log

- 2026-04-24T07:47:39Z – claude – lane=doing – d
- 2026-04-24T07:47:47Z – claude – lane=testing – t
- 2026-04-24T07:47:53Z – claude – lane=for_review – r
