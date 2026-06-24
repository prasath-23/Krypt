# Control Map --- 002 Manual Lock + Permission Clarity

## Flows

| Flow | Purpose | Key Files |
|------|---------|-----------|
| Onboarding permission clarity | Mandatory vs Optional split, live ✓/✗ indicators, mandatory progress bar, Skip on Optional steps | `app/src/main/java/com/krypt/app/permission/PermissionKey.kt`, `permission/PermissionClassification.kt`, `permission/PermissionStatusProbe.kt`, `permission/PermissionStateObserver.kt`, `permission/PermissionIndicator.kt`, `ui/onboarding/OnboardingScreen.kt`, `ui/onboarding/OnboardingViewModel.kt`, `ui/onboarding/OnboardingStep.kt` |
| Home Screen manual app lock manager | Searchable installed-apps list, one-way toggle (ON locks instantly, OFF dispatches Guardian request) | `app/src/main/java/com/krypt/app/ui/home/HomeScreen.kt`, `ui/home/HomeViewModel.kt`, `ui/home/InstalledAppsRepository.kt`, `ui/home/InstalledAppRow.kt`, `ui/main/MainActivity.kt` (navigation rewire) |

## Shared Dependencies

| Component | Used By | Path |
|-----------|---------|------|
| `LockedAppsRepository` | Home Screen toggle (read + write), feature 001 auto-locker | `app/src/main/java/com/krypt/app/data/LockedAppsRepository.kt` |
| `UnlockRequestBuilder` | Home Screen "attempted unlock" path | `app/src/main/java/com/krypt/app/deeplink/UnlockRequestBuilder.kt` |
| `MasterKeyStore` | Home Screen "attempted unlock" reads salt + pinProof to embed in `krypt://request` | `app/src/main/java/com/krypt/app/security/MasterKeyStore.kt` |
| `MainActivity` navigation | Onboarding completion -> Home Screen routing change | `app/src/main/java/com/krypt/app/ui/main/MainActivity.kt` |
| Hilt DI graph | New `PermissionStatusProbe`, `PermissionStateObserver`, `InstalledAppsRepository` bindings | `app/src/main/java/com/krypt/app/di/CoreModule.kt` (or new `UiModule.kt`) |

## Cross-Flow Notes

- The two flows share **no UI surface**: the onboarding indicators live solely in onboarding (FR-025), and the Home Screen exposes no permission state.
- The two flows share the **navigation graph**: the new `OnboardingViewModel` decides "onboarding complete" based on Mandatory progress = 3/3 plus PIN setup configured (the existing Amendment-1 gate from feature 001). On completion it routes to the new `HomeScreen` rather than the old placeholder.
- The two flows share the **Hilt graph**: a new `UiModule` (or extension of `CoreModule`) provides bindings for `PermissionStatusProbe`, `PermissionStateObserver`, and `InstalledAppsRepository`. Both flows are reachable in the same `Activity` lifecycle.
