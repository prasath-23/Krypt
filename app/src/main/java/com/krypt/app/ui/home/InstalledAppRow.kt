package com.krypt.app.ui.home

import android.graphics.Bitmap
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.ui.common.dayText
import com.krypt.app.ui.theme.PermissionGrantedGreen
import java.time.ZoneId

/**
 * A single installed-app row on the Home Screen.
 *
 * [onToggle] is called regardless of the OS-reported checked value, so the
 * ViewModel decides the outcome; the switch always shows [state.isLocked],
 * even while a Guardian unlock is running. The line under the name says
 * what applies right now ([AppRowStatus]), and [onLockNow] ends an unlock
 * early.
 */
@Composable
fun InstalledAppRow(
    state: InstalledAppRowState,
    iconCache: AppIconCache,
    onToggle: () -> Unit,
    onLockNow: () -> Unit,
    onEndDailyTime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var icon by remember(state.packageName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(state.packageName) { icon = iconCache.loadAsync(state.packageName) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(bitmap = icon, modifier = Modifier.size(48.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            when (val status = state.status) {
                AppRowStatus.NotLocked -> Unit
                AppRowStatus.Locked -> Text(
                    text = stringResource(R.string.home_label_locked),
                    style = MaterialTheme.typography.labelSmall,
                    color = PermissionGrantedGreen,
                )
                is AppRowStatus.Unlocked -> {
                    Text(
                        text = pluralStringResource(
                            R.plurals.home_status_unlocked,
                            status.minutesLeft,
                            status.minutesLeft,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onLockNow) {
                        Text(stringResource(R.string.home_action_lock_now))
                    }
                }
                is AppRowStatus.Daily -> {
                    Text(
                        text = dailyLineText(status.line),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onEndDailyTime) {
                        Text(stringResource(R.string.home_action_end_daily))
                    }
                }
            }
        }

        Switch(
            checked = state.isLocked,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun dailyLineText(line: DailyLine): String {
    val until = dayText(LocalContext.current, line.lastDay, ZoneId.systemDefault())
    return when (line) {
        is DailyLine.Left -> pluralStringResource(
            R.plurals.home_status_daily_left, line.minutesLeft, line.minutesLeft, line.minutesPerDay, until,
        )
        is DailyLine.UsedUp -> stringResource(R.string.home_status_daily_used_up, until)
        is DailyLine.Paused -> stringResource(R.string.home_status_daily_paused, until)
    }
}

@Composable
private fun AppIcon(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    val bmp: ImageBitmap? = bitmap?.asImageBitmap()
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
