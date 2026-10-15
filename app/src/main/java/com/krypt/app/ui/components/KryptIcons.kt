package com.krypt.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.krypt.app.R
import com.krypt.app.ui.theme.KryptSize

/**
 * Material Icons Rounded, bundled as vector drawables (res/drawable/ic_*.xml,
 * from docs/design-system/assets/Icons). Tint them with the colour of their
 * meaning; see the table in assets/Icons/README.md.
 */
object KryptIcons {
    @DrawableRes val Lock = R.drawable.ic_lock
    @DrawableRes val LockOpen = R.drawable.ic_lock_open
    @DrawableRes val LockClock = R.drawable.ic_lock_clock
    @DrawableRes val HourglassBottom = R.drawable.ic_hourglass_bottom
    @DrawableRes val Schedule = R.drawable.ic_schedule
    @DrawableRes val Timer = R.drawable.ic_timer
    @DrawableRes val CheckCircle = R.drawable.ic_check_circle
    @DrawableRes val Cancel = R.drawable.ic_cancel
    @DrawableRes val Error = R.drawable.ic_error
    @DrawableRes val Share = R.drawable.ic_share
    @DrawableRes val ContentPaste = R.drawable.ic_content_paste
    @DrawableRes val Link = R.drawable.ic_link
    @DrawableRes val Home = R.drawable.ic_home
    @DrawableRes val OpenInNew = R.drawable.ic_open_in_new
    @DrawableRes val Backspace = R.drawable.ic_backspace
    @DrawableRes val Search = R.drawable.ic_search
    @DrawableRes val ChevronRight = R.drawable.ic_chevron_right
    @DrawableRes val ArrowForward = R.drawable.ic_arrow_forward
    @DrawableRes val AccessibilityNew = R.drawable.ic_accessibility_new
    @DrawableRes val Layers = R.drawable.ic_layers
    @DrawableRes val BatteryAlert = R.drawable.ic_battery_alert
    @DrawableRes val AdminPanelSettings = R.drawable.ic_admin_panel_settings
    @DrawableRes val Notifications = R.drawable.ic_notifications
    @DrawableRes val Info = R.drawable.ic_info
    @DrawableRes val Close = R.drawable.ic_close
    @DrawableRes val Contrast = R.drawable.ic_contrast

    /** Reserved for appearance hints. */
    @DrawableRes val LightMode = R.drawable.ic_light_mode
    @DrawableRes val DarkMode = R.drawable.ic_dark_mode
    @DrawableRes val SettingsBrightness = R.drawable.ic_settings_brightness
}

/**
 * One of [KryptIcons]. An icon beside its own word is decorative (pass a
 * null [contentDescription]); every other icon needs a label.
 */
@Composable
fun KryptIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = KryptSize.iconMd,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/**
 * The Krypt mark: a keyhole with a cat's ears. One flat silhouette in a
 * single colour; never outlined, rotated, circled or given a face. Below
 * 24dp it is the optical small size, without the inner keyhole (the full
 * mark is never shrunk that far).
 */
@Composable
fun KryptMark(
    modifier: Modifier = Modifier,
    height: Dp = KryptSize.iconSm,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(if (height < FULL_MARK_MIN) R.drawable.ic_krypt_mark_sm else R.drawable.ic_krypt_mark),
        contentDescription = null,
        modifier = modifier.height(height).width(height * MARK_ASPECT),
        tint = tint,
    )
}

/** Both marks' own box is 36 x 66. */
private const val MARK_ASPECT = 36f / 66f

/** The smallest size the full mark is drawn at (assets/Logos/README.md). */
private val FULL_MARK_MIN = 24.dp
