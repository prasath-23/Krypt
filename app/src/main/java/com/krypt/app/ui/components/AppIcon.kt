package com.krypt.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import com.krypt.app.ui.home.AppIconCache
import com.krypt.app.ui.theme.KryptShape
import com.krypt.app.ui.theme.KryptSize
import kotlinx.coroutines.CancellationException

/**
 * An installed app's icon, masked to [shape]. Until it loads (or if it
 * can't), a tile with the app name's first letter stands in. Decorative:
 * the app's name is always written beside it.
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String,
    iconCache: AppIconCache,
    modifier: Modifier = Modifier,
    size: Dp = KryptSize.appIconRow,
    shape: Shape = KryptShape.sm,
) {
    var icon by remember(packageName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(packageName) {
        icon = try {
            iconCache.loadAsync(packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // uninstalled meanwhile: the letter tile stays
        }
    }
    val loaded = icon
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.firstOrNull()?.uppercase().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
