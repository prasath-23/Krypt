package com.krypt.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.krypt.app.R

/*
 * Three bundled families (SIL OFL; licences in assets/licenses). Fonts are
 * never loaded at run time. Instrument Sans and Geist Mono are variable
 * fonts, so each weight sets its own `wght` axis.
 */

/** Statements and titles. Always regular; italic only for the duration in the summary sentence. */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** Everything people read or tap. */
val InstrumentSans = FontFamily(
    variableFont(R.font.instrument_sans, 400),
    variableFont(R.font.instrument_sans, 500),
    variableFont(R.font.instrument_sans, 600),
    variableFont(R.font.instrument_sans, 700),
)

/** Numbers that tick or must be exact: package names, times, the lockout countdown. */
val GeistMono = FontFamily(
    variableFont(R.font.geist_mono, 300),
    variableFont(R.font.geist_mono, 400),
    variableFont(R.font.geist_mono, 500),
)

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val KryptLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    family: FontFamily,
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.sp,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing,
    lineHeightStyle = KryptLineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * Material's slots mapped onto Krypt's type tokens:
 * display-large / display-small / headline are the serif statements,
 * labelSmall is the eyebrow.
 */
val KryptTypography: Typography = Typography(
    displayLarge = style(InstrumentSerif, 48, 52, letterSpacing = (-0.5).sp),
    displayMedium = style(InstrumentSerif, 40, 46, letterSpacing = (-0.4).sp),
    displaySmall = style(InstrumentSerif, 34, 40, letterSpacing = (-0.25).sp),
    headlineLarge = style(InstrumentSerif, 30, 36),
    headlineMedium = style(InstrumentSerif, 28, 34),
    headlineSmall = style(InstrumentSerif, 26, 32),
    titleLarge = style(InstrumentSans, 20, 28, FontWeight.SemiBold),
    titleMedium = style(InstrumentSans, 16, 24, FontWeight.SemiBold),
    titleSmall = style(InstrumentSans, 14, 20, FontWeight.SemiBold),
    bodyLarge = style(InstrumentSans, 16, 24),
    bodyMedium = style(InstrumentSans, 14, 20),
    bodySmall = style(InstrumentSans, 12, 16),
    labelLarge = style(InstrumentSans, 15, 20, FontWeight.SemiBold, 0.1.sp),
    labelMedium = style(InstrumentSans, 13, 16, FontWeight.Medium, 0.2.sp),
    labelSmall = style(InstrumentSans, 11, 16, FontWeight.SemiBold, 1.2.sp),
)

/** Krypt's styles that have no Material slot. */
@Immutable
data class KryptTextStyles(
    /** The small-capitals line above a title; write those strings in capitals. */
    val eyebrow: TextStyle,
    /** Sheets and the summary sentence. */
    val headline: TextStyle,
    val monoPackage: TextStyle,
    /** Times that tick, e.g. "4:59". */
    val monoTime: TextStyle,
    /** Inside the lockout ring. */
    val monoCountdown: TextStyle,
    /** Digits on Krypt's PIN keypad. */
    val keypad: TextStyle,
)

val KryptTextStyleTokens = KryptTextStyles(
    eyebrow = KryptTypography.labelSmall,
    headline = KryptTypography.headlineSmall,
    monoPackage = style(GeistMono, 12, 16),
    monoTime = style(GeistMono, 14, 20, FontWeight.Medium),
    monoCountdown = style(GeistMono, 32, 36, FontWeight.Light, (-0.5).sp),
    keypad = style(InstrumentSans, 26, 32),
)

internal val LocalKryptTextStyles = staticCompositionLocalOf { KryptTextStyleTokens }
