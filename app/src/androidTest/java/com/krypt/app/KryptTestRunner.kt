package com.krypt.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.testing.WorkManagerTestInitHelper
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

    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        // HiltTestApplication doesn't set up WorkManager (KryptApplication does),
        // but a health-check job Krypt scheduled on this device earlier can still
        // start WorkManager's SystemJobService in the test process. A test
        // WorkManager keeps that from crashing the whole run.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }
}
