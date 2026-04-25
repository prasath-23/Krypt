package com.krypt.app.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton LRU cache for app icons indexed by package name.
 *
 * The cache budget is capped at [MAX_BYTES] (16 MB) to avoid OOM on devices
 * with hundreds of installed apps. Eviction is automatic when the budget is
 * exceeded. The cache is NOT thread-safe for *mutation* of the underlying
 * [Drawable] objects — callers MUST treat the returned instance as read-only.
 *
 * Use [loadAsync] from a Compose [androidx.compose.runtime.produceState] key to
 * lazy-load per row without blocking the UI thread.
 */
@Singleton
class AppIconCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lru = object : LruCache<String, Drawable>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Drawable): Int {
            return if (value is BitmapDrawable) {
                value.bitmap?.byteCount ?: FALLBACK_ICON_BYTES
            } else {
                FALLBACK_ICON_BYTES
            }
        }
    }

    /**
     * Synchronous icon load (must be called on a background thread / IO dispatcher).
     * Returns a generic placeholder on [android.content.pm.PackageManager.NameNotFoundException].
     */
    fun load(packageName: String): Drawable {
        lru.get(packageName)?.let { return it }
        val icon = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            context.packageManager.defaultActivityIcon
        }
        lru.put(packageName, icon)
        return icon
    }

    /** Coroutine-safe wrapper for use from Compose [androidx.compose.runtime.produceState]. */
    suspend fun loadAsync(packageName: String): Drawable =
        withContext(Dispatchers.IO) { load(packageName) }

    companion object {
        const val MAX_BYTES: Int = 16 * 1024 * 1024 // 16 MB
        private const val FALLBACK_ICON_BYTES: Int = 64 * 64 * 4 // 64x64 ARGB estimate
    }
}
