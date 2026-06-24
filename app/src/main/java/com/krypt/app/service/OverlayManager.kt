package com.krypt.app.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.krypt.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-001 / FR-002: fullscreen non-dismissible overlay that covers locked apps.
 * Pre-inflated at service start to hit the <200ms overlay-latency budget
 * (SC-001). See research.md R2 for the flag rationale.
 */
@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var overlayView: View? = null
    @Volatile private var isShowingFlag: Boolean = false
    @Volatile private var currentPackage: String? = null
    @Volatile private var askGuardianCallback: ((String) -> Unit)? = null

    /** Pre-inflate + wire the Ask Guardian click. Call from service onCreate. */
    fun preinflate() = runOnMain {
        if (overlayView != null) return@runOnMain
        val view = View.inflate(context, R.layout.locker_overlay, null)
        view.isFocusable = true
        view.setOnKeyListener { _, keyCode, _ ->
            // Consume Back; Home/Recents are delivered to SystemUI, not us,
            // so the Accessibility Service re-asserts on next foreground event.
            keyCode == KeyEvent.KEYCODE_BACK
        }
        view.findViewById<Button>(R.id.btn_ask_guardian).setOnClickListener {
            currentPackage?.let { pkg -> askGuardianCallback?.invoke(pkg) }
        }
        overlayView = view
    }

    /** Wire the "Ask Guardian" action; typically points at WP14 share logic. */
    fun bindAskGuardian(onClick: (pkg: String) -> Unit) {
        askGuardianCallback = onClick
    }

    fun show(pkg: String, displayName: String, iconDrawable: Drawable?) = runOnMain {
        if (overlayView == null) preinflate()
        val view = overlayView ?: return@runOnMain

        if (isShowingFlag && currentPackage == pkg) return@runOnMain
        if (isShowingFlag) {
            updateContentInternal(pkg, displayName, iconDrawable)
            return@runOnMain
        }
        updateContentInternal(pkg, displayName, iconDrawable)
        try {
            windowManager.addView(view, buildLayoutParams())
            isShowingFlag = true
            applyImmersive(view)
        } catch (t: Throwable) {
            // OEMs may deny SYSTEM_ALERT_WINDOW silently; swallow + reset state.
            isShowingFlag = false
        }
    }

    fun hide() = runOnMain {
        val view = overlayView ?: return@runOnMain
        if (!isShowingFlag) return@runOnMain
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // Already detached; ignore.
        }
        isShowingFlag = false
        currentPackage = null
    }

    fun updateContent(pkg: String, displayName: String, iconDrawable: Drawable?) = runOnMain {
        updateContentInternal(pkg, displayName, iconDrawable)
    }

    fun isShowing(): Boolean = isShowingFlag

    private fun updateContentInternal(pkg: String, displayName: String, iconDrawable: Drawable?) {
        val view = overlayView ?: return
        currentPackage = pkg
        view.findViewById<ImageView>(R.id.locked_app_icon).setImageDrawable(iconDrawable)
        view.findViewById<TextView>(R.id.locked_app_name).text = displayName
        view.findViewById<TextView>(R.id.locked_app_package).text = pkg
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_SECURE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun applyImmersive(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = view.windowInsetsController ?: return
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            view.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}
