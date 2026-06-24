//
// app/build.gradle.kts — Krypt application module.
//
// Pins minSdk 29 (Android 10) through targetSdk 35 (Android 15) per plan Technical
// Context. Uses Jetpack Compose + Material3 for Activity UI, Hilt for DI, Room for
// persistence, and standard AndroidX crypto. Does NOT declare any networking
// library or the INTERNET permission — Krypt is air-gapped by design (FR-005).
//

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.krypt.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.krypt.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"

        // WP05/WP23: Custom runner bootstraps HiltTestApplication so
        // instrumented tests can drive @HiltAndroidRule + @TestInstallIn
        // overrides (Amendment1* e2e tests in androidTest/).
        testInstrumentationRunner = "com.krypt.app.KryptTestRunner"

        // Export the Room schema JSON so schema drift shows up in code review.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
                arguments["room.incremental"] = "true"
            }
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        debug {
            // Signing via the default debug key; no network egress regardless.
            isMinifyEnabled = false
        }
        release {
            // R8 / ProGuard rules are populated in WP18.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // Duplicate META-INF files across androidx + coroutines are expected; exclude noisily.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    // ── AndroidX core ──────────────────────────────────────────────────────────────
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    // ── Compose ────────────────────────────────────────────────────────────────────
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)

    // ── Hilt ───────────────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

    // ── Room ───────────────────────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // ── Kotlin coroutines ──────────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // ── Unit tests (JVM, src/test) ─────────────────────────────────────────────────
    testImplementation(libs.junit4)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // ── Instrumented tests (src/androidTest) ───────────────────────────────────────
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kaptAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Ensure Kapt stubs + Room codegen work cleanly on JDK 17+ (needed for toolchain matching).
kapt {
    correctErrorTypes = true
}

// -------------------------------------------------------------------------
// verifyManifest — WP18 SC-008 gate. Fails the build if the debug APK's
// manifest declares android.permission.INTERNET. Wired into ./gradlew check.
// -------------------------------------------------------------------------
tasks.register("verifyManifest") {
    group = "verification"
    description = "Fails the build if INTERNET permission appears in the assembled debug APK."
    dependsOn("assembleDebug")
    doLast {
        val apk = file("build/outputs/apk/debug/app-debug.apk")
        require(apk.exists()) { "APK not found at $apk; did assembleDebug succeed?" }
        val buildToolsDir = android.sdkDirectory.resolve("build-tools")
        val aapt = buildToolsDir.listFiles()?.sortedBy { it.name }?.lastOrNull()
            ?.resolve(if (org.gradle.internal.os.OperatingSystem.current().isWindows) "aapt.exe" else "aapt")
            ?: error("aapt not found under $buildToolsDir; install Android SDK build-tools")
        val processBuilder = ProcessBuilder(aapt.absolutePath, "dump", "permissions", apk.absolutePath)
        processBuilder.redirectErrorStream(true)
        val output = processBuilder.start().inputStream.bufferedReader().readText()
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
        )
        val violations = forbidden.filter { output.contains(it) }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Manifest declares forbidden permission(s): ${violations.joinToString()} (FR-005). " +
                    "See polaris-specs/001-krypt-app-locker/spec.md section 5.\n\nAudit output:\n$output"
            )
        }
        logger.lifecycle("OK: APK does not declare INTERNET or other network-adjacent permissions.")
    }
}

tasks.named("check") {
    dependsOn("verifyManifest")
}

// Lint: enforce content-description on Icon/Image (WP18 T087).
android.lint {
    error += "MissingContentDescription"
    warningsAsErrors = false
}
