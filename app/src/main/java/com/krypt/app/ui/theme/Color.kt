package com.krypt.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * "Soft neutral", from docs/design-system/tokens.json. Backgrounds are pure
 * white and Android's #121212; near-black / near-white ink is `primary`.
 * Colour appears only when it means something (KryptColors). The window
 * background in res/values{,-night}/colors.xml must match `background`.
 */

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1F1F1F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Color(0xFF1F1F1F),
    inversePrimary = Color(0xFFE8E8E8),
    secondary = Color(0xFF5F6368),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF1F1F1F),
    // Krypt has no tertiary role; keep anything Material reaches for neutral.
    tertiary = Color(0xFF1F1F1F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDEDED),
    onTertiaryContainer = Color(0xFF1F1F1F),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF5F6368),
    // No tonal tint: grouping is done with hairlines and surface containers.
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2E2E2E),
    inverseOnSurface = Color(0xFFF2F2F2),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE4E1),
    onErrorContainer = Color(0xFF4A0F0A),
    outline = Color(0xFF767B80),
    outlineVariant = Color(0xFFE3E3E3),
    scrim = Color(0x66000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E3E3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE3E3E3),
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE8E8E8),
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFF1F1F1F),
    secondary = Color(0xFFA8A8A8),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF232323),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFFE8E8E8),
    onTertiary = Color(0xFF121212),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF2E2E2E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1410),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF2C2C2C),
    scrim = Color(0x99000000),
    surfaceBright = Color(0xFF2C2C2C),
    surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF2C2C2C),
)

/**
 * Krypt's own roles beside Material's: the colours that carry a meaning.
 * Always shown with an icon and words, never colour alone.
 *
 * - [locked]: the ink itself, for "Locked".
 * - [open]: teal, for an app open now or daily time left.
 * - [caution]: burnt orange, for done for today or paused.
 * - [granted]: green, for "Allowed" in onboarding.
 * - [flash] / [onFlash]: mint, only for the approval moment, never under text.
 */
@Immutable
data class KryptColors(
    val locked: Color,
    val lockedContainer: Color,
    val onLockedContainer: Color,
    val open: Color,
    val openContainer: Color,
    val onOpenContainer: Color,
    val caution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val granted: Color,
    val flash: Color,
    val onFlash: Color,
    val focusRing: Color,
)

val LightKryptColors = KryptColors(
    locked = Color(0xFF1F1F1F),
    lockedContainer = Color(0xFFEDEDED),
    onLockedContainer = Color(0xFF1F1F1F),
    open = Color(0xFF1F6F5C),
    openContainer = Color(0xFFDDF0EA),
    onOpenContainer = Color(0xFF0A2A22),
    caution = Color(0xFF9A4A12),
    cautionContainer = Color(0xFFFBE6D7),
    onCautionContainer = Color(0xFF2E1607),
    granted = Color(0xFF1D7A3A),
    flash = Color(0xFF3CCB8C),
    onFlash = Color(0xFF121212),
    focusRing = Color(0xFF1F1F1F),
)

val DarkKryptColors = KryptColors(
    locked = Color(0xFFE8E8E8),
    lockedContainer = Color(0xFF2A2A2A),
    onLockedContainer = Color(0xFFF2F2F2),
    open = Color(0xFF7CCFB8),
    openContainer = Color(0xFF15302A),
    onOpenContainer = Color(0xFFD5F0EA),
    caution = Color(0xFFF5A06A),
    cautionContainer = Color(0xFF3A220F),
    onCautionContainer = Color(0xFFF8E2D2),
    granted = Color(0xFF6DD58C),
    flash = Color(0xFF3CCB8C),
    onFlash = Color(0xFF121212),
    focusRing = Color(0xFFE8E8E8),
)

internal val LocalKryptColors = staticCompositionLocalOf { LightKryptColors }
