package com.krypt.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme

/** A whole-screen problem in one sentence, on `error-container`. */
@Composable
fun ErrorPanel(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KryptShape.lg)
            .background(colors.errorContainer)
            .padding(KryptSpace.s4)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s3),
        verticalAlignment = Alignment.Top,
    ) {
        KryptIcon(KryptIcons.Error, contentDescription = null, tint = colors.onErrorContainer)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
            color = colors.onErrorContainer,
        )
    }
}

/** A short caution note, e.g. under the Guardian's days choice. */
@Composable
fun NotePanel(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = KryptIcons.Info,
) {
    val krypt = KryptTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KryptShape.md)
            .background(krypt.cautionContainer)
            .padding(horizontal = KryptSpace.s4, vertical = KryptSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s2),
        verticalAlignment = Alignment.Top,
    ) {
        KryptIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp),
            tint = krypt.onCautionContainer,
            size = KryptSize.iconSm,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = krypt.onCautionContainer)
    }
}

/** One inline error line in the error colour, announced when it appears. */
@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s1),
        verticalAlignment = Alignment.Top,
    ) {
        KryptIcon(
            icon = KryptIcons.Error,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp),
            tint = colors.error,
            size = KryptSize.iconSm,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = colors.error)
    }
}

/**
 * A grouped section: `surface-container-low` at `radius-lg`. Separate its
 * rows with [GroupDivider]; each row pads itself (16dp).
 */
@Composable
fun KryptGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KryptShape.lg)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

/** The 1dp `outline-variant` hairline between rows. */
@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = KryptSize.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
