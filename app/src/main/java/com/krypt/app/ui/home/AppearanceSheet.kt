package com.krypt.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.krypt.app.R
import com.krypt.app.data.settings.Appearance
import com.krypt.app.ui.components.HintText
import com.krypt.app.ui.components.KryptSheet
import com.krypt.app.ui.theme.KryptMotion
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptSpace
import com.krypt.app.ui.theme.KryptTheme
import kotlinx.coroutines.launch

/**
 * The Appearance sheet (docs/design-system/components/ThemeSetting), opened
 * from the contrast button in Home's header: System default, Light and Dark
 * as three cards side by side, each with a miniature of its theme.
 *
 * Choosing a card closes the sheet and then calls [onSelect]: the new theme
 * may recreate the screen, so the sheet is out of the way first. [onDismiss]
 * is called once the sheet has gone, whichever way it closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    selected: Appearance,
    onSelect: (Appearance) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    // The tapped card shows as chosen while the sheet slides away.
    var chosen by remember(selected) { mutableStateOf(selected) }
    var closing by remember { mutableStateOf(false) }

    KryptSheet(onDismissRequest = { latestOnDismiss() }, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.appearance),
            modifier = Modifier.semantics { heading() },
            style = KryptTheme.text.headline,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(KryptSpace.s3),
        ) {
            AppearanceChoices.forEach { choice ->
                AppearanceCard(
                    choice = choice,
                    selected = choice.value == chosen,
                    onClick = {
                        if (!closing) {
                            closing = true
                            chosen = choice.value
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                latestOnDismiss()
                                latestOnSelect(choice.value)
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
        HintText(text = stringResource(R.string.appearance_note))
    }
}

/**
 * One choice: its miniature, name and line. A radio button for TalkBack,
 * read as its name and line, and "selected". The chosen card has a 2dp
 * `primary` ring, the others a hairline.
 */
@Composable
private fun AppearanceCard(
    choice: AppearanceChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ringColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outlineVariant,
        animationSpec = tween(KryptMotion.FAST_MS, easing = KryptMotion.Easing),
        label = "appearanceRing",
    )
    val ringWidth by animateDpAsState(
        targetValue = if (selected) SelectedRing else KryptSize.hairline,
        animationSpec = tween(KryptMotion.FAST_MS, easing = KryptMotion.Easing),
        label = "appearanceRingWidth",
    )
    Column(
        modifier = modifier
            .clip(KryptShape.lg)
            .border(ringWidth, ringColor, KryptShape.lg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(
                start = KryptSpace.s2,
                top = KryptSpace.s2,
                end = KryptSpace.s2,
                bottom = KryptSpace.s3,
            ),
    ) {
        ThemePreview(
            appearance = choice.value,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PREVIEW_ASPECT),
        )
        Text(
            text = stringResource(choice.name),
            modifier = Modifier.padding(top = KryptSpace.s2),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Text(
            text = stringResource(choice.line),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/**
 * A miniature screen in each theme; System default is split half light,
 * half dark. Its colours are fixed on purpose: they show what Light and Dark
 * look like, not the theme Krypt is in now.
 */
@Composable
private fun ThemePreview(appearance: Appearance, modifier: Modifier = Modifier) {
    val shape = KryptShape.sm
    Canvas(
        modifier = modifier
            .clip(shape)
            .border(KryptSize.hairline, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        when (appearance) {
            Appearance.SYSTEM -> {
                val half = Size(size.width / 2f, size.height)
                drawMiniScreen(LightPreview, Offset.Zero, half, split = true)
                drawMiniScreen(DarkPreview, Offset(size.width / 2f, 0f), half, split = true)
            }
            Appearance.LIGHT -> drawMiniScreen(LightPreview, Offset.Zero, size, split = false)
            Appearance.DARK -> drawMiniScreen(DarkPreview, Offset.Zero, size, split = false)
        }
    }
}

/**
 * One theme's miniature in the [area] at [topLeft]: its background, a title
 * line near the top and a button bar near the bottom, in proportions from
 * ThemeSetting's preview. A half of the split preview draws them a little
 * wider, so each half still reads as a screen.
 */
private fun DrawScope.drawMiniScreen(theme: PreviewColors, topLeft: Offset, area: Size, split: Boolean) {
    drawRect(color = theme.background, topLeft = topLeft, size = area)

    val barInset = area.width * (if (split) BAR_INSET_SPLIT else BAR_INSET)
    val barHeight = area.height * BAR_HEIGHT
    drawRoundRect(
        color = theme.bar,
        topLeft = Offset(
            x = topLeft.x + barInset,
            y = topLeft.y + area.height * (1f - BAR_BOTTOM) - barHeight,
        ),
        size = Size(area.width - 2f * barInset, barHeight),
        cornerRadius = CornerRadius(barHeight * CORNER),
    )

    val lineHeight = area.height * LINE_HEIGHT
    drawRoundRect(
        color = theme.line,
        topLeft = Offset(
            x = topLeft.x + area.width * LINE_INSET,
            y = topLeft.y + area.height * LINE_TOP,
        ),
        size = Size(area.width * (if (split) LINE_WIDTH_SPLIT else LINE_WIDTH), lineHeight),
        cornerRadius = CornerRadius(lineHeight * CORNER),
    )
}

private data class AppearanceChoice(
    val value: Appearance,
    @StringRes val name: Int,
    @StringRes val line: Int,
)

/** Three options only, always in this order. */
private val AppearanceChoices = listOf(
    AppearanceChoice(Appearance.SYSTEM, R.string.appearance_system, R.string.appearance_system_line),
    AppearanceChoice(Appearance.LIGHT, R.string.appearance_light, R.string.appearance_light_line),
    AppearanceChoice(Appearance.DARK, R.string.appearance_dark, R.string.appearance_dark_line),
)

private class PreviewColors(val background: Color, val bar: Color, val line: Color)

/** Light: the white background, the near-black primary bar and an `outline-variant` line. */
private val LightPreview = PreviewColors(
    background = Color(0xFFFFFFFF),
    bar = Color(0xFF1F1F1F),
    line = Color(0xFFE3E3E3),
)

/** Dark: Android's #121212, the near-white primary bar and an `outline-variant` line. */
private val DarkPreview = PreviewColors(
    background = Color(0xFF121212),
    bar = Color(0xFFE8E8E8),
    line = Color(0xFF2C2C2C),
)

private val SelectedRing = KryptSize.hairline * 2

/** Width to height of a miniature: a portrait phone. */
private const val PREVIEW_ASPECT = 3f / 4f

// The miniature's proportions, as fractions of its width or height.
private const val BAR_INSET = 0.18f
private const val BAR_INSET_SPLIT = 0.14f
private const val BAR_BOTTOM = 0.14f
private const val BAR_HEIGHT = 0.12f
private const val LINE_INSET = 0.18f
private const val LINE_TOP = 0.18f
private const val LINE_WIDTH = 0.46f
private const val LINE_WIDTH_SPLIT = 0.60f
private const val LINE_HEIGHT = 0.09f
private const val CORNER = 0.25f
