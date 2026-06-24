package com.krypt.app.subject

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.krypt.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WP22 Amendment 1: success UX played by [ApprovalTrampolineActivity] on a
 * valid unlock approval.
 *
 * Components:
 *   - Haptic: 50 ms one-shot vibration (API 29+).
 *   - Green flash: `R.id.unlock_flash` alpha fades 0 -> 0.6 -> 0 over 500 ms.
 *   - Toast: "Unlocked <short-name> until HH:mm" (short-form so it doesn't
 *     linger on-screen in the way Toast.LENGTH_LONG would).
 *   - Accessibility announcement if TalkBack is active: "Unlocked by Guardian".
 *
 * Intentionally platform-typed: the whole job is one-shot UI + a vibrator
 * call, so pure-JVM testability is not required. Robolectric covers it.
 */
@Singleton
class UnlockSuccessEffect @Inject constructor() {

    /**
     * Run the full success effect on the main thread. Suspends until the
     * green-flash animation completes so the caller can finish the Activity
     * afterwards.
     */
    suspend fun play(
        activity: Activity,
        targetPackage: String,
        grantExpiresAtMs: Long,
    ) {
        withContext(Dispatchers.Main) {
            playHaptic(activity)
            announce(activity)
            showToast(activity, targetPackage, grantExpiresAtMs)
        }
        playFlash(activity)
    }

    private fun playHaptic(context: Context) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } catch (_: Throwable) {
            // Non-critical; a missing vibrator must not fail an unlock.
        }
    }

    private fun announce(context: Context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return
        if (!am.isEnabled || !am.isTouchExplorationEnabled) return
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
            className = UnlockSuccessEffect::class.java.name
            packageName = context.packageName
            text.add(context.getString(R.string.approval_a11y_announcement))
        }
        am.sendAccessibilityEvent(event)
    }

    private fun showToast(
        context: Context,
        targetPackage: String,
        grantExpiresAtMs: Long,
    ) {
        val pm = context.packageManager
        val label = runCatching {
            pm.getApplicationLabel(
                pm.getApplicationInfo(targetPackage, 0),
            ).toString()
        }.getOrDefault(targetPackage)
        val until = DateFormat.getTimeFormat(context).format(Date(grantExpiresAtMs))
        val msg = context.getString(R.string.approval_toast_unlocked, label, until)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private suspend fun playFlash(activity: Activity) {
        val flashView: View? = activity.findViewById(R.id.unlock_flash)
        if (flashView == null) {
            delay(FLASH_DURATION_MS)
            return
        }
        withContext(Dispatchers.Main) {
            ObjectAnimator
                .ofFloat(flashView, "alpha", 0f, 0.6f, 0f)
                .setDuration(FLASH_DURATION_MS)
                .start()
        }
        delay(FLASH_DURATION_MS)
    }

    @VisibleForTesting
    companion object {
        const val FLASH_DURATION_MS = 500L
        const val HAPTIC_MS = 50L
    }
}
