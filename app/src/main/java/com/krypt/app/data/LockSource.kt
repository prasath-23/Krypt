package com.krypt.app.data

/**
 * Why an app is in the locked-apps table.
 *  - [DEFAULT_DENY] inserted by NewInstallLocker on a new install.
 *  - [MANUAL] added from the Home Screen lock toggle.
 */
enum class LockSource { DEFAULT_DENY, MANUAL }
