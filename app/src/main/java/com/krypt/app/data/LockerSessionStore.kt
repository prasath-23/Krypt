package com.krypt.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.krypt.app.common.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live unlock grants, keyed by package name: what decides whether a locked
 * app may open right now.
 *
 * The Accessibility Service (WP10) reads this on every foreground event
 * (10-30/s on busy devices). A `ConcurrentHashMap` lookup is ~100 ns; a
 * Room read is 2-10 ms.
 *
 * Expiry runs on [Clock.elapsedMs], which the user cannot change, so moving
 * the device clock neither stretches a grant nor brings an ended one back.
 * Each grant is also saved on that clock, so a restart of Krypt's process
 * keeps it ([restore], called from KryptApplication). A reboot restarts the
 * clock and ends every grant: the Subject asks the Guardian again.
 *
 * Updated by ApprovalTrampolineActivity on every consumed approval. The
 * `unlock_grants` rows in Room record which request authorised each grant;
 * they are not read back.
 */
@Singleton
class LockerSessionStore internal constructor(
    private val prefs: SharedPreferences,
    private val clock: Clock,
) {

    @Inject constructor(@ApplicationContext context: Context, clock: Clock) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), clock)

    /** Package name -> grant expiry on the [Clock.elapsedMs] timeline. */
    private val cache: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    private val _grants = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Live snapshot of the grants (package name -> expiry on the
     * [Clock.elapsedMs] timeline), for screens that show what is unlocked.
     * An entry can outlive its expiry until [remainingMs] is next asked about
     * it, so use [remainingMs] rather than comparing times.
     */
    val grants: StateFlow<Map<String, Long>> = _grants.asStateFlow()

    private val _grantEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /**
     * Emits the package name each time a grant is recorded or restored, e.g.
     * so its lock screen can close and its expiry can be watched.
     */
    val grantEvents: SharedFlow<String> = _grantEvents.asSharedFlow()

    /** O(1) synchronous read. Safe from any thread. */
    fun isUnlockedNow(pkg: String): Boolean = remainingMs(pkg) > 0

    /** Milliseconds left on [pkg]'s grant; 0 when it has none. */
    fun remainingMs(pkg: String): Long {
        val expiresAt = cache[pkg] ?: return 0
        val remaining = expiresAt - clock.elapsedMs()
        if (remaining > 0) return remaining
        // Opportunistic cleanup; caller stays correct even if we race here.
        if (cache.remove(pkg, expiresAt)) synchronized(this) { publish() }
        return 0
    }

    /** Packages that currently have an active grant. */
    fun grantedPackages(): Set<String> = cache.keys.filterTo(HashSet()) { isUnlockedNow(it) }

    /** Record a grant that ends at wall-clock [expiresAtMs]. Never shortens an existing grant. */
    @Synchronized
    fun recordGrant(pkg: String, expiresAtMs: Long) {
        val remaining = expiresAtMs - clock.nowMs()
        if (remaining <= 0) return
        val expiresAt = cache.merge(pkg, clock.elapsedMs() + remaining, ::maxOf) ?: return
        save(pkg, expiresAt)
        publish()
        _grantEvents.tryEmit(pkg)
    }

    /** End [pkg]'s grant now, e.g. because the app was locked again. */
    @SuppressLint("ApplySharedPref") // see the commit() below
    @Synchronized
    fun revoke(pkg: String) {
        cache.remove(pkg)
        prefs.edit().remove(KEY_GRANT_PREFIX + pkg).commit() // must not come back on a restart
        publish()
    }

    @Synchronized
    fun expireAll() {
        cache.clear()
        prefs.edit().clear().apply()
        publish()
    }

    /**
     * Bring back the grants saved before Krypt's process last stopped, with
     * the time they had left. Grants saved in an earlier boot are dropped, as
     * are all saved grants when the boot can't be identified.
     */
    @Synchronized
    fun restore() {
        val boot = clock.bootCount()
        if (boot == null || prefs.getInt(KEY_BOOT, NO_BOOT) != boot) {
            prefs.edit().clear().apply()
            return
        }
        val now = clock.elapsedMs()
        val ended = mutableListOf<String>()
        for ((key, expiresAt) in prefs.all) {
            if (!key.startsWith(KEY_GRANT_PREFIX) || expiresAt !is Long) continue
            if (expiresAt <= now) {
                ended += key
                continue
            }
            val pkg = key.removePrefix(KEY_GRANT_PREFIX)
            cache.merge(pkg, expiresAt, ::maxOf)
            _grantEvents.tryEmit(pkg)
        }
        if (ended.isNotEmpty()) {
            val editor = prefs.edit()
            ended.forEach { editor.remove(it) }
            editor.apply()
        }
        publish()
    }

    /** Callers hold this object's lock, so a newer snapshot is never overwritten by an older one. */
    private fun publish() {
        _grants.value = HashMap(cache)
    }

    /** Losing this write only ends the grant early, so it needn't block. */
    private fun save(pkg: String, expiresAt: Long) {
        val boot = clock.bootCount() ?: return // could never be restored
        val editor = prefs.edit()
        if (prefs.getInt(KEY_BOOT, NO_BOOT) != boot) editor.clear() // left from an earlier boot
        editor.putInt(KEY_BOOT, boot).putLong(KEY_GRANT_PREFIX + pkg, expiresAt).apply()
    }

    /** Approximate size — for diagnostics only. */
    fun approximateSize(): Int = cache.size

    private companion object {
        const val PREFS_NAME = "krypt_unlock_grants"
        const val KEY_BOOT = "boot"
        const val KEY_GRANT_PREFIX = "grant:"
        const val NO_BOOT = -1
    }
}
