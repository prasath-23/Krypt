package com.krypt.app.permission

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.krypt.app.R
import com.krypt.app.ui.theme.PermissionGrantedGreen

/**
 * Animated ✓/✗ indicator for a single permission's grant state (FR-023, FR-024).
 *
 * Transitions between granted/denied states via a cross-fade + scale-in animation
 * lasting 200-300 ms. The icon carries an accessibility content description so
 * TalkBack users hear the state without the visual.
 */
@Composable
fun PermissionIndicator(
    granted: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = granted,
        transitionSpec = {
            (fadeIn(tween(300)) + scaleIn(initialScale = 0.7f, animationSpec = tween(300)))
                .togetherWith(fadeOut(tween(200)))
        },
        modifier = modifier,
        label = "PermissionIndicator",
    ) { isGranted ->
        if (isGranted) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.permission_indicator_granted),
                tint = PermissionGrantedGreen,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = stringResource(R.string.permission_indicator_denied),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
