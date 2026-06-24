package com.krypt.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom `AndroidJUnitRunner` that substitutes [HiltTestApplication] for the
 * real [KryptApplication], so `@HiltAndroidRule` can bootstrap a test-only
 * dependency graph in instrumented tests.
 *
 * Wire-up is in `app/build.gradle.kts`:
 *   testInstrumentationRunner = "com.krypt.app.KryptTestRunner"
 */
class KryptTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
