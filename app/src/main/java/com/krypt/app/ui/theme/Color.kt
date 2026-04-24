package com.krypt.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val KryptPurple = Color(0xFF2E1A47)
internal val KryptPurpleDark = Color(0xFF1A0A28)
internal val KryptAmber = Color(0xFFF3C98B)
internal val KryptError = Color(0xFFB3261E)

val LightColorScheme = lightColorScheme(
    primary = KryptPurple,
    onPrimary = Color.White,
    secondary = KryptAmber,
    onSecondary = KryptPurple,
    error = KryptError,
    background = Color(0xFFF7F4FA),
    onBackground = KryptPurpleDark,
)

val DarkColorScheme = darkColorScheme(
    primary = KryptAmber,
    onPrimary = KryptPurpleDark,
    secondary = KryptPurple,
    onSecondary = Color.White,
    error = KryptError,
    background = KryptPurpleDark,
    onBackground = Color(0xFFF7F4FA),
)
