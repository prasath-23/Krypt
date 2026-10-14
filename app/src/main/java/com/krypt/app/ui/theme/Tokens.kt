package com.krypt.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

/** Spacing on a 4dp grid (`space-*` in docs/design-system/tokens.json). */
object KryptSpace {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s12 = 48.dp
    val s16 = 64.dp

    /** Text starts at this gutter at every width. */
    val gutter = s5
}

/** Fixed sizes (`touch-target`, `icon-*`, `button-height` ...). */
object KryptSize {
    val touchTarget = 48.dp
    val hairline = 1.dp
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val appIconRow = 40.dp
    val appIconTile = 56.dp
    val buttonHeight = 52.dp
    val buttonHeightSm = 36.dp
    val fieldHeight = 52.dp
    val keyHeight = 60.dp
    val pinDot = 12.dp

    /** Content never grows wider than this column. */
    val columnMax = 400.dp
}

/** Quiet and quick (`motion-*`). */
object KryptMotion {
    /** Presses, selection, the switch. */
    const val FAST_MS = 120

    /** Revealing a field, changing a status. */
    const val BASE_MS = 220

    /** Onboarding's Allowed / Not allowed state (the spec allows 200-500 ms). */
    const val INDICATOR_MS = 280

    /** The approval flash (the spec requires at least 500 ms). */
    const val FLASH_MS = 700

    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
