package com.krypt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Krypt's theme. Light or dark follows the Appearance setting
 * ([com.krypt.app.data.settings.AppearanceStore]): it sets the app's night
 * mode, so [isSystemInDarkTheme] already reflects System default, Light or
 * Dark, and the window backgrounds (res/values-night) agree with Compose.
 */
@Composable
fun KryptTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalKryptColors provides if (darkTheme) DarkKryptColors else LightKryptColors,
        LocalKryptTextStyles provides KryptTextStyleTokens,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = KryptTypography,
            shapes = KryptShapes,
            content = content,
        )
    }
}

/** Krypt's roles beside [MaterialTheme]: `KryptTheme.colors.open`, `KryptTheme.text.eyebrow`. */
object KryptTheme {
    val colors: KryptColors
        @Composable @ReadOnlyComposable get() = LocalKryptColors.current

    val text: KryptTextStyles
        @Composable @ReadOnlyComposable get() = LocalKryptTextStyles.current
}
