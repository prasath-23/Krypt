package com.krypt.app.data

/**
 * Why an app is in the locked-apps table.
 *  - [DEFAULT_DENY] inserted by PackageReceiver on a new install.
 *  - [MANUAL] added by the Administrator via the settings UI.
 */
enum class LockSource { DEFAULT_DENY, MANUAL }
