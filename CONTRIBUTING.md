# Contributing to Krypt

## The one hard rule: no network

Krypt's defining property is that it ships with zero network capability. The
manifest MUST NOT declare:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.ACCESS_WIFI_STATE`
- `android.permission.CHANGE_WIFI_STATE`
- `android.permission.CHANGE_NETWORK_STATE`

The `verifyManifest` Gradle task (wired into `./gradlew check`) inspects
the assembled APK via `aapt dump permissions` and fails the build if any
of these appear. CI runs this on every PR. Do not bypass it.

If you have a legitimate reason to add network capability, it requires a
spec amendment (`polaris-specs/001-krypt-app-locker/spec.md` section 5,
FR-005) and explicit approval from the feature owner before the PR is
considered.

## Third-party libraries

Krypt ships without third-party networking, UI, or crypto libraries. All
dependencies must be first-party AndroidX / Jetpack / Kotlin / Dagger Hilt.
See `gradle/libs.versions.toml` for the allowed list.

## Commit messages

- Use conventional-commit prefixes (`feat:`, `fix:`, `chore:`, etc.).
- Include the `Co-Authored-By: Aptean Polaris <polaris@aptean.com>` trailer.
- Reference the Polaris WP this commit belongs to (e.g.
  `Refs: polaris-specs/001-krypt-app-locker/tasks/WP01-project-scaffold.md`).

## Tests

- JVM unit tests (`./gradlew testDebugUnitTest`) must stay green.
- Instrumented tests (`./gradlew connectedDebugAndroidTest`) are run manually
  before every merge on a connected device.
- `tests/krypt-app-locker/docs/manual-test-script.md` is the authoritative
  manual checklist for non-automatable SCs.
