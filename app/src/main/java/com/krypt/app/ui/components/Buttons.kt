package com.krypt.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace

/*
 * Three button weights and one inline size, all with soft corners
 * (docs/design-system/components/Button). Labels are sentence-case verbs of
 * at most three words. Heights are minimums, so buttons grow with font scale.
 */

/**
 * The one action a screen exists for; never two on one screen. Full width.
 * While [busy] it shows a spinner before [busyText] and ignores taps.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String = text,
    @DrawableRes icon: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = KryptSize.buttonHeight),
        shape = KryptShape.md,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.onSurface.copy(alpha = DISABLED_CONTAINER),
            disabledContentColor = colors.onSurface.copy(alpha = DISABLED_CONTENT),
        ),
        contentPadding = PaddingValues(horizontal = KryptSpace.s5, vertical = KryptSpace.s3),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(KryptSpace.s2))
            Text(busyText, textAlign = TextAlign.Center)
        } else {
            if (icon != null) {
                KryptIcon(icon, contentDescription = null)
                Spacer(Modifier.width(KryptSpace.s2))
            }
            Text(text, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A real alternative that leaves Krypt, e.g. "Allow in settings". Its
 * trailing `open_in_new` announces the hand-off before it happens.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes trailingIcon: Int? = KryptIcons.OpenInNew,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = KryptSize.buttonHeight),
        shape = KryptShape.md,
        border = BorderStroke(
            KryptSize.hairline,
            if (enabled) colors.outline else colors.onSurface.copy(alpha = DISABLED_CONTAINER),
        ),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
        contentPadding = PaddingValues(horizontal = KryptSpace.s5, vertical = KryptSpace.s3),
    ) {
        Text(text, textAlign = TextAlign.Center)
        if (trailingIcon != null) {
            Spacer(Modifier.width(KryptSpace.s2))
            KryptIcon(trailingIcon, contentDescription = null, size = KryptSize.iconSm)
        }
    }
}

/** Text only, with a 48dp target: Paste reply, Back to home, Keep it locked. */
@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = KryptSize.touchTarget),
        shape = KryptShape.md,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = KryptSpace.s3, vertical = KryptSpace.s2),
    ) {
        if (icon != null) {
            KryptIcon(icon, contentDescription = null)
            Spacer(Modifier.width(KryptSpace.s2))
        }
        Text(text, textAlign = TextAlign.Center)
    }
}

/** The inline size, for actions inside a row, e.g. "Allow in settings" in onboarding. */
@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes trailingIcon: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = KryptSize.buttonHeightSm),
        shape = KryptShape.sm,
        border = BorderStroke(KryptSize.hairline, colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
        contentPadding = PaddingValues(horizontal = KryptSpace.s4, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp),
            textAlign = TextAlign.Center,
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(6.dp))
            KryptIcon(trailingIcon, contentDescription = null, size = KryptSize.iconSm)
        }
    }
}

/** `disabled-container` and `disabled-content` opacities. */
private const val DISABLED_CONTAINER = 0.10f
private const val DISABLED_CONTENT = 0.38f
