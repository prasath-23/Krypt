package com.krypt.app.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AppIconCache].
 */
class AppIconCacheTest {

    private val ctx = mockk<Context>(relaxed = true)
    private val pm = mockk<PackageManager>(relaxed = true)
    private val defaultIcon = mockk<Bitmap>(relaxed = true)
    private val appIcon = mockk<Bitmap>(relaxed = true)
    private lateinit var cache: AppIconCache

    private val defaultDrawable = mockk<Drawable>(relaxed = true)
    private val appDrawable = mockk<Drawable>(relaxed = true)

    @Before
    fun setUp() {
        io.mockk.mockkStatic("androidx.core.graphics.drawable.DrawableKt")
        every { ctx.packageManager } returns pm
        every { pm.defaultActivityIcon } returns defaultDrawable
        every { defaultDrawable.toBitmap() } returns defaultIcon
        every { appDrawable.toBitmap() } returns appIcon
        cache = AppIconCache(ctx)
    }

    @Test
    fun load_returnsIcon_forKnownPackage() {
        every { pm.getApplicationIcon("com.example.app") } returns appDrawable

        val result = cache.load("com.example.app")

        assertSame(appIcon, result)
    }

    @org.junit.Ignore("LruCache returns null when isReturnDefaultValues = true, breaking caching logic")
    @Test
    fun load_returnsCachedIcon_onSecondCall() {
        every { pm.getApplicationIcon("com.example.app") } returns appDrawable

        val first = cache.load("com.example.app")
        val second = cache.load("com.example.app")

        assertSame(first, second)
        verify(exactly = 1) { pm.getApplicationIcon("com.example.app") }
    }

    @Test
    fun load_returnsDefault_whenPackageNotFound() {
        every { pm.getApplicationIcon("com.not.installed") } throws
            PackageManager.NameNotFoundException("not found")

        val result = cache.load("com.not.installed")

        assertSame(defaultIcon, result)
    }

    @Test
    fun load_returnsDefault_onGenericException() {
        every { pm.getApplicationIcon("com.bad.package") } throws RuntimeException("unexpected")

        val result = cache.load("com.bad.package")

        assertSame(defaultIcon, result)
    }

    @Test
    fun loadAsync_delegatesToLoad() = runTest {
        every { pm.getApplicationIcon("com.example.async") } returns appDrawable

        val result = cache.loadAsync("com.example.async")

        assertSame(appIcon, result)
    }
}
