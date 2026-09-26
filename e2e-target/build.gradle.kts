//
// e2e-target — a stand-in "newly installed app" for Krypt's end-to-end tests
// (tests/e2e/krypt_e2e.py). Installing it exercises the new-install
// auto-lock; launching it exercises blocking and Guardian unlocks.
//
// Framework classes only: no dependencies, no network permission. Never
// shipped.
//

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.krypt.e2e.target"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.krypt.e2e.target"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
