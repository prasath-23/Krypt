# Krypt proguard-rules.pro (WP18)
#
# Keeps every class that a manifest, DI, or reflection system references by
# name. Without these, R8 can rename / strip entries that the framework then
# fails to resolve at runtime.

# --- Hilt ---
-keep class dagger.hilt.internal.** { *; }
-keep class * extends javax.inject.Provider
-keep class * extends dagger.hilt.android.internal.managers.** { *; }

# --- Room (entities + DAOs are reflection-accessed) ---
-keep class com.krypt.app.data.** { *; }
-keep interface com.krypt.app.data.** { *; }

# --- Kotlin metadata (Compose + reflection) ---
-keep @kotlin.Metadata class * { *; }
-keepclassmembers class **$Companion { *; }

# --- Compose ---
-keepclassmembers class androidx.compose.** { *; }

# --- DataStore Preferences ---
-keep class androidx.datastore.preferences.** { *; }

# --- WorkManager ---
-keep class androidx.work.** { *; }
-keep @androidx.hilt.work.HiltWorker class * { *; }

# --- Manifest-referenced Krypt entry points ---
-keep class com.krypt.app.KryptApplication { *; }
-keep class com.krypt.app.ui.main.MainActivity { *; }
-keep class com.krypt.app.ui.guardian.GuardianActivity { *; }
-keep class com.krypt.app.service.AppLockerAccessibilityService { *; }
-keep class com.krypt.app.service.KryptDeviceAdminReceiver { *; }
-keep class com.krypt.app.service.KryptWatchdogService { *; }
-keep class com.krypt.app.receiver.PackageReceiver { *; }
-keep class com.krypt.app.worker.AccessibilityHealthWorker { *; }

# --- Reflection in Room migrations + RoomDatabase.Builder ---
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- Preserve line numbers for crash reports ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
