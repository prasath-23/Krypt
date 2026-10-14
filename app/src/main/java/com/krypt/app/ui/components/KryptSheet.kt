package com.krypt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.krypt.app.ui.theme.KryptSpace

/**
 * A Krypt bottom sheet: `surface-container-high`, `radius-xl` top corners
 * and a small grabber. Sheets are the only surfaces with a shadow.
 *
 * To close it with its animation, hide [sheetState] and then remove the
 * sheet from composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KryptSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = KryptSpace.s2)
                    .size(width = 32.dp, height = 4.dp)
                    .background(colors.outline.copy(alpha = GRABBER_ALPHA), CircleShape),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = KryptSpace.gutter, end = KryptSpace.gutter, bottom = KryptSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(KryptSpace.s3),
            content = content,
        )
    }
}

private const val GRABBER_ALPHA = 0.5f
