package com.krypt.app.ui.home

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.krypt.app.ui.theme.PermissionGrantedGreen

/**
 * A single installed-app row on the Home Screen.
 *
 * The toggle is deliberately one-directional (FR-033): [onToggle] is called
 * regardless of the OS-reported checked/unchecked value so the ViewModel
 * decides the outcome. The toggle visual state is always bound to [state.isLocked].
 */
@Composable
fun InstalledAppRow(
    state: InstalledAppRowState,
    iconCache: AppIconCache,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: Drawable? by produceState<Drawable?>(initialValue = null, state.packageName) {
        value = iconCache.loadAsync(state.packageName)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(drawable = icon, modifier = Modifier.size(48.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            if (state.isLocked) {
                Text(
                    text = "Locked",
                    style = MaterialTheme.typography.labelSmall,
                    color = PermissionGrantedGreen,
                )
            }
        }

        Switch(
            checked = state.isLocked,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun AppIcon(drawable: Drawable?, modifier: Modifier = Modifier) {
    val bmp: ImageBitmap? = drawable?.toBitmap()?.asImageBitmap()
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Spacer(modifier = modifier)
    }
}
