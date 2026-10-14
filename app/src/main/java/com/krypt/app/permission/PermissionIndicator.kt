package com.krypt.app.permission

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.ui.components.KryptIcon
import com.krypt.app.ui.components.KryptIcons
import com.krypt.app.ui.theme.KryptMotion
import com.krypt.app.ui.theme.KryptSize
import com.krypt.app.ui.theme.KryptTheme

/**
 * One permission's live state in onboarding's checklist
 * (docs/design-system/components/PermissionStep): "Allowed" with
 * `check_circle` in `granted`, or `cancel` in `on-surface-variant` with
 * "Not allowed yet" for a required permission and "Not allowed" for an
 * optional one.
 *
 * A change fades in while the icon pops from 0.6 scale over
 * `motion-indicator` (the spec allows 200-500 ms), like `kx-pop` in the
 * design. The icon is decorative: TalkBack reads the word.
 *
 * Indicators appear only in onboarding.
 */
@Composable
fun PermissionIndicator(
    granted: Boolean,
    optional: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = granted,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(KryptMotion.INDICATOR_MS, easing = KryptMotion.Easing))
                .togetherWith(fadeOut(tween(KryptMotion.FAST_MS, easing = KryptMotion.Easing)))
        },
        label = "PermissionIndicator",
    ) { isGranted ->
        val pop = Modifier.animateEnterExit(
            enter = scaleIn(
                animationSpec = tween(KryptMotion.INDICATOR_MS, easing = KryptMotion.Easing),
                initialScale = POP_FROM_SCALE,
            ),
            exit = ExitTransition.None,
        )
        val color = if (isGranted) KryptTheme.colors.granted else MaterialTheme.colorScheme.onSurfaceVariant
        val word = when {
            isGranted -> R.string.permission_allowed
            optional -> R.string.permission_not_allowed
            else -> R.string.permission_not_allowed_yet
        }
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(INDICATOR_GAP),
        ) {
            KryptIcon(
                icon = if (isGranted) KryptIcons.CheckCircle else KryptIcons.Cancel,
                contentDescription = null,
                modifier = pop,
                tint = color,
                size = KryptSize.iconSm,
            )
            Text(
                text = stringResource(word),
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

/** `kx-pop` starts the icon at this scale. */
private const val POP_FROM_SCALE = 0.6f

/** The gap between the icon and its word (`.kx-perm`). */
private val INDICATOR_GAP = 6.dp
