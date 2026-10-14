package com.krypt.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.ui.components.AppIcon
import com.krypt.app.ui.components.StatusLine
import com.krypt.app.ui.components.statusText
import com.krypt.app.ui.theme.KryptMotion
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace

/**
 * One installed app in Home (docs/design-system/components/AppRow): its
 * icon, name, one status line and the lock switch. Every row has the same
 * shape and no buttons inside; the list separates rows with hairlines.
 *
 * The switch shows the lock setting: on = locked, and it stays on while an
 * unlock or daily time runs. [onToggle] is called whatever the switch
 * reports, so the caller decides what a tap means (lock at once, or ask
 * before removing a lock).
 *
 * The row pads its own content with the 20dp gutter, so a highlight and the
 * press ripple reach across the whole column.
 *
 * @param onOpenOptions makes the whole row tappable, for rows open now (e.g.
 *   to offer Relock now); null for rows with nothing to offer.
 * @param highlighted fills the row with `primary-container`, e.g. for the app
 *   a "New app protected" notification pointed at; the caller turns it off.
 */
@Composable
fun InstalledAppRow(
    state: InstalledAppRowState,
    iconCache: AppIconCache,
    onToggle: () -> Unit,
    onOpenOptions: (() -> Unit)?,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = colors.primaryContainer
    // Fades the fill's alpha only, so the colour never passes through grey.
    val highlightFill by animateColorAsState(
        targetValue = if (highlighted) highlight else highlight.copy(alpha = 0f),
        animationSpec = tween(KryptMotion.BASE_MS, easing = KryptMotion.Easing),
        label = "rowHighlight",
    )
    val switchLabel = stringResource(R.string.home_switch_a11y, state.displayName)
    val tapModifier = if (onOpenOptions != null) {
        val rowLabel = stringResource(
            R.string.home_row_a11y_options,
            state.displayName,
            statusText(state.status).text,
        )
        Modifier
            .clickable(role = Role.Button, onClick = onOpenOptions)
            .semantics { contentDescription = rowLabel }
    } else {
        // The name and status read as one; the switch keeps its own label.
        Modifier.semantics(mergeDescendants = true) {}
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawRect(highlightFill) }
            .then(tapModifier)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = KryptSpace.gutter, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KryptSpace.s3),
    ) {
        AppIcon(
            packageName = state.packageName,
            label = state.displayName,
            iconCache = iconCache,
            size = KryptSize.appIconRow,
            shape = KryptShape.sm,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StatusLine(status = state.status)
        }
        Switch(
            checked = state.isLocked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics { contentDescription = switchLabel },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = colors.outline,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
}

/** AppRow: every row is at least 68dp tall, whatever its status. */
private val RowMinHeight = 68.dp

/** The switch's 48dp touch target fills a 68dp row exactly; taller text keeps this margin. */
private val RowVerticalPadding = (RowMinHeight - KryptSize.touchTarget) / 2
