package com.krypt.app.permission

/**
 * Synchronous, stateless query of the OS-level grant state for each
 * [PermissionKey]. Implementations MUST be idempotent and produce no
 * side-effects — they are called every time the observing Activity resumes.
 */
interface PermissionStatusProbe {

    /** Returns true iff the OS-level permission for [key] is currently granted. */
    fun statusOf(key: PermissionKey): Boolean

    /** Convenience: returns the grant state for all known keys. */
    fun statusAll(): Map<PermissionKey, Boolean> =
        PermissionKey.values().associateWith { statusOf(it) }
}
