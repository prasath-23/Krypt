package com.krypt.app.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.krypt.app.R
import kotlin.math.roundToInt

/**
 * The countdown over the app in front in its last minutes (Amendment 7): a
 * small pill at the top of the screen, "1:59 left", like the indicator a
 * scroll counter keeps over the apps it counts.
 *
 * The accessibility service draws it as an accessibility overlay, so it
 * needs no other permission, and it takes no focus and no touches: the app
 * under it works as before. It is always dark, like a toast, so it reads the
 * same over any app, light or dark. Its icon is the timer in `open` teal,
 * then the hourglass in `caution` orange for the last [ENDING_MS].
 */
class TimeLeftPill(private val service: AccessibilityService) : TimeLeftDisplay {

    private val windowManager: WindowManager = service.getSystemService(WindowManager::class.java)
    private var pill: PillView? = null

    override fun show(pkg: String, msLeft: Long) {
        pill?.let { return it.show(msLeft) }
        // Filled in before its window is added, so its first frame already shows the time.
        val view = PillView(service).apply { show(msLeft) }
        if (add(view)) pill = view
    }

    override fun hide() {
        val view = pill ?: return
        pill = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // Already gone: Android removes a service's overlays when it is unbound.
        }
    }

    private fun add(view: PillView): Boolean = try {
        windowManager.addView(view, layoutParams())
        Log.i(TAG, "countdown shown")
        true
    } catch (e: RuntimeException) {
        // The service was unbound a moment ago, say, and its window token is gone.
        Log.w(TAG, "could not show the countdown", e)
        false
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // It sits in a layer above the status bar, which doesn't keep it clear, so it is laid out from
        // the top of the screen and put below the status bar by hand, on every Android version alike.
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = statusBarHeight() + service.dp(TOP_DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowAnimations = android.R.style.Animation_Toast
        title = WINDOW_TITLE
    }

    /** The status bar's height, with any camera cutout in it, also while the app in front hides it. */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                .top
        }
        // Android 10 has no window metrics; this is the height it gives its status bar.
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) service.resources.getDimensionPixelSize(id) else 0
    }

    /**
     * The pill itself. Android sends a window event when it shows; that event
     * carries [ACCESSIBILITY_CLASS], so the service can tell it isn't an app
     * coming to the front.
     */
    private class PillView(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val label = TextView(context)
        private val mono: Typeface? = ResourcesCompat.getFont(context, R.font.geist_mono)
        private var shownText: String? = null
        private var shownEnding: Boolean? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = PILL_RADIUS
                setColor(BACKGROUND)
                setStroke(context.dp(1), HAIRLINE)
            }
            setPadding(context.dp(12), context.dp(8), context.dp(14), context.dp(8))
            addView(icon, LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply { marginEnd = context.dp(8) })
            label.setTextColor(TEXT)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            label.typeface = ResourcesCompat.getFont(context, R.font.instrument_sans)
            label.fontVariationSettings = "'wght' 500"
            label.includeFontPadding = false
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(label)
        }

        fun show(msLeft: Long) {
            val ending = msLeft <= ENDING_MS
            if (ending != shownEnding) {
                shownEnding = ending
                icon.setImageResource(if (ending) R.drawable.ic_hourglass_bottom else R.drawable.ic_timer)
                icon.imageTintList = ColorStateList.valueOf(if (ending) CAUTION else OPEN)
            }
            val time = timeLeftText(msLeft)
            val text = context.getString(R.string.time_left_pill, time)
            if (text == shownText) return
            shownText = text
            // The time in Geist Mono, like every time that ticks in Krypt.
            label.text = SpannableString(text).apply {
                val at = text.indexOf(time)
                if (at >= 0 && mono != null) setSpan(TypefaceSpan(mono), at, at + time.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            contentDescription = text
        }

        override fun getAccessibilityClassName(): CharSequence = ACCESSIBILITY_CLASS
    }

    companion object {
        /** The class name the pill's window event carries. */
        const val ACCESSIBILITY_CLASS = "com.krypt.app.service.TimeLeftPill"

        /** Its window's title, which `adb shell dumpsys window windows` shows (the e2e tests look for it). */
        const val WINDOW_TITLE = "KryptTimeLeft"

        /** The last stretch, when the timer icon turns to the hourglass in `caution` orange. */
        const val ENDING_MS = 30_000L

        private const val TAG = "KryptA11y"
        private const val TOP_DP = 8
        private const val ICON_DP = 16 // icon-sm, as in status lines
        private const val TEXT_SP = 15f
        private const val PILL_RADIUS = 1_000f // more than half its height: round ends

        // Krypt's tokens (docs/design-system/tokens.json): inverse-surface and inverse-on-surface of the
        // light theme, and the dark theme's open and caution, which read on that surface.
        private const val BACKGROUND = 0xFF2E2E2E.toInt()
        private const val TEXT = 0xFFF2F2F2.toInt()
        private const val HAIRLINE = 0x33FFFFFF // lifts it off dark apps
        private const val OPEN = 0xFF7CCFB8.toInt()
        private const val CAUTION = 0xFFF5A06A.toInt()
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
