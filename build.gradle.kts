//
// Root build.gradle.kts
//
// Declares the plugin *classpath* for the project; each plugin is applied per-module
// with `apply false` here so Gradle downloads them once but only activates them
// where they are referenced via `alias(libs.plugins.xxx)`.
//

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
}
