package com.krypt.app.ui.home

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
    private val defaultIcon = mockk<Drawable>(relaxed = true)
    private val appIcon = mockk<Drawable>(relaxed = true)
    private lateinit var cache: AppIconCache

    @Before
    fun setUp() {
        every { ctx.packageManager } returns pm
        every { pm.defaultActivityIcon } returns defaultIcon
        cache = AppIconCache(ctx)
    }

    @Test
    fun load_returnsIcon_forKnownPackage() {
        every { pm.getApplicationIcon("com.example.app") } returns appIcon

        val result = cache.load("com.example.app")

        assertSame(appIcon, result)
    }

    @Test
    fun load_returnsCachedIcon_onSecondCall() {
        every { pm.getApplicationIcon("com.example.app") } returns appIcon

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
        every { pm.getApplicationIcon("com.example.async") } returns appIcon

        val result = cache.loadAsync("com.example.async")

        assertSame(appIcon, result)
    }
}
