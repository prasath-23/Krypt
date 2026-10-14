package com.krypt.app.subject

import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.krypt.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WP22 Amendment 1: success UX played by [ApprovalTrampolineActivity] on a
 * valid unlock approval (docs/design-system/components/UnlockFlash).
 *
 * Components:
 *   - Haptic: 50 ms one-shot vibration (API 29+).
 *   - Accessibility announcement if TalkBack is active: "Opened by your Guardian".
 *   - The flash: a mint field (`R.id.unlock_flash`) at 94% opacity with a
 *     ringed check (`R.id.unlock_flash_mark`) that settles from 70% to full
 *     size, over [FLASH_DURATION_MS] (the spec requires at least 500 ms).
 *     With animations removed it shows without motion for
 *     [STATIC_FLASH_MS].
 *   - Then the toast: the caller's message, e.g. "Instagram is open until
 *     14:35" (short, so it doesn't linger).
 *
 * Intentionally platform-typed: the whole job is one-shot UI + a vibrator
 * call, so pure-JVM testability is not required.
 */
@Singleton
class UnlockSuccessEffect @Inject constructor() {

    /**
     * Run the full success effect on the main thread. Suspends until the
     * flash has finished and the toast is up, so the caller can open the
     * app and finish the Activity afterwards.
     */
    suspend fun play(
        activity: Activity,
        message: String,
    ) {
        withContext(Dispatchers.Main) {
            playHaptic(activity)
            announce(activity)
        }
        playFlash(activity)
        withContext(Dispatchers.Main) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
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

    private suspend fun playFlash(activity: Activity) {
        val field: View? = activity.findViewById(R.id.unlock_flash)
        val mark: View? = activity.findViewById(R.id.unlock_flash_mark)
        if (field == null || mark == null) {
            delay(FLASH_DURATION_MS)
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // "Remove animations": the field shows without motion for 500 ms.
            withContext(Dispatchers.Main) {
                field.alpha = FIELD_ALPHA
                mark.alpha = 1f
            }
            delay(STATIC_FLASH_MS)
            withContext(Dispatchers.Main) {
                field.alpha = 0f
                mark.alpha = 0f
            }
            return
        }
        withContext(Dispatchers.Main) {
            mark.scaleX = MARK_START_SCALE
            mark.scaleY = MARK_START_SCALE
            AnimatorSet().apply {
                playTogether(
                    fade(field, FIELD_ALPHA),
                    fade(mark, 1f),
                    settle(mark),
                )
                start()
            }
        }
        delay(FLASH_DURATION_MS)
    }

    /** Fades [view] in to [peak], holds it, and fades it out again. */
    private fun fade(view: View, peak: Float): ObjectAnimator {
        val alpha = PropertyValuesHolder.ofKeyframe(
            View.ALPHA,
            Keyframe.ofFloat(0f, 0f),
            Keyframe.ofFloat(FADE_IN_END, peak),
            Keyframe.ofFloat(FADE_OUT_START, peak),
            Keyframe.ofFloat(1f, 0f),
        )
        return ObjectAnimator.ofPropertyValuesHolder(view, alpha).apply {
            duration = FLASH_DURATION_MS
            interpolator = LinearInterpolator()
        }
    }

    /** The ring settles from 70% to full size in the first 30% of the flash. */
    private fun settle(view: View): ObjectAnimator {
        val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
        fun scale(property: android.util.Property<View, Float>) = PropertyValuesHolder.ofKeyframe(
            property,
            Keyframe.ofFloat(0f, MARK_START_SCALE),
            Keyframe.ofFloat(SETTLE_END, 1f).apply { interpolator = easing },
            Keyframe.ofFloat(1f, 1f),
        )
        return ObjectAnimator.ofPropertyValuesHolder(view, scale(View.SCALE_X), scale(View.SCALE_Y)).apply {
            duration = FLASH_DURATION_MS
            interpolator = LinearInterpolator()
        }
    }

    @VisibleForTesting
    companion object {
        /** `motion-flash`; FR-021 requires at least 500 ms. */
        const val FLASH_DURATION_MS = 700L
        const val STATIC_FLASH_MS = 500L
        const val HAPTIC_MS = 50L

        private const val FIELD_ALPHA = 0.94f
        private const val MARK_START_SCALE = 0.7f
        private const val FADE_IN_END = 0.18f
        private const val FADE_OUT_START = 0.78f
        private const val SETTLE_END = 0.3f
    }
}
