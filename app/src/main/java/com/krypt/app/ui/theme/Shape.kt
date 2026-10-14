package com.krypt.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Soft, not round (`radius-*` tokens): there are no pill buttons in Krypt.
 * Only dots, the switch and badges are fully round.
 */
object KryptShape {
    /** App icons, keys, chips. */
    val sm = RoundedCornerShape(10.dp)

    /** Buttons, fields, segments. */
    val md = RoundedCornerShape(14.dp)

    /** Grouped sections, the lock screen's app tile. */
    val lg = RoundedCornerShape(22.dp)

    /** Sheets. */
    val xl = RoundedCornerShape(28.dp)
}

val KryptShapes: Shapes = Shapes(
    extraSmall = KryptShape.sm,
    small = KryptShape.sm,
    medium = KryptShape.md,
    large = KryptShape.lg,
    extraLarge = KryptShape.xl,
)
