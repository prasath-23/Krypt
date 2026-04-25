package com.krypt.app.permission

/** Programmable [PermissionStatusProbe] for use across unit tests. */
class FakePermissionStatusProbe(allGranted: Boolean) : PermissionStatusProbe {
    private val granted = PermissionKey.values().associateWith { allGranted }.toMutableMap()
    var pollCount: Int = 0
        private set

    fun grant(key: PermissionKey) { granted[key] = true }
    fun revoke(key: PermissionKey) { granted[key] = false }

    override fun statusOf(key: PermissionKey): Boolean {
        pollCount++
        return granted[key] ?: false
    }
}
