package com.krypt.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krypt.app.ui.theme.KryptMotion
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize

/**
 * A segmented single choice, e.g. Once / Daily: a hairline-bordered track
 * whose selected segment fills with `primary`. Each segment is a radio
 * button for TalkBack; [groupLabel] names the group.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    groupLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(KryptSize.hairline, colors.outline, KryptShape.md)
            .padding(SEGMENT_INSET)
            .semantics { contentDescription = groupLabel }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_INSET),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val container by animateColorAsState(
                targetValue = if (isSelected) colors.primary else Color.Transparent,
                animationSpec = tween(KryptMotion.FAST_MS),
                label = "segment",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
                    .clip(SEGMENT_SHAPE)
                    .background(container)
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A single-choice chip: a hairline at rest, filled with `primary` when
 * selected. There are no checkmarks; the fill is the selection, and
 * TalkBack reads "selected".
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        animationSpec = tween(KryptMotion.FAST_MS),
        label = "chip",
    )
    Box(
        modifier = modifier
            // A 36dp chip with the 48dp touch target every control gets.
            .minimumInteractiveComponentSize()
            .heightIn(min = KryptSize.buttonHeightSm)
            .clip(KryptShape.sm)
            .background(container)
            .border(KryptSize.hairline, if (selected) Color.Transparent else colors.outline, KryptShape.sm)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }
}

private val SEGMENT_INSET = 3.dp
private val SEGMENT_SHAPE = RoundedCornerShape(11.dp)
