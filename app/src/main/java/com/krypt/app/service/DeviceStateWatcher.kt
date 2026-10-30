package com.krypt.app.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.krypt.app.common.TrustedDayClock
import com.krypt.app.data.daily.DailyAllowanceMeter

/**
 * Tells the every-day meter and the day clock what the device is doing:
 * the screen going off, on and unlocked; the clock or time zone being
 * changed; automatic date & time being switched. [onScreen] hears whether
 * the screen is on and unlocked, as the meter does. [recheck] is called when
 * the app in front may have to be blocked now - after unlocking, since a
 * grant or today's time may have run out while the screen was off.
 *
 * Runs while the accessibility service does. Needs no permission.
 */
class DeviceStateWatcher(
    private val context: Context,
    private val meter: DailyAllowanceMeter,
    private val dayClock: TrustedDayClock,
    private val onScreen: (interactive: Boolean) -> Unit,
    private val recheck: () -> Unit,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SHUTDOWN -> setInteractive(false)
                Intent.ACTION_SCREEN_ON -> if (!keyguardLocked()) userIsBack()
                Intent.ACTION_USER_PRESENT -> userIsBack()
                Intent.ACTION_TIME_CHANGED -> {
                    dayClock.onTimeSet()
                    meter.reevaluate()
                    recheck()
                }
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    meter.reevaluate()
                    recheck()
                }
            }
        }
    }

    private val autoTimeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            dayClock.onAutoTimeChanged()
            meter.reevaluate()
            recheck()
        }
    }

    private var started = false

    /** What was last said, so the log has one line per change. */
    private var lastInteractive: Boolean? = null

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.AUTO_TIME),
            false,
            autoTimeObserver,
        )
        started = true
        val power = context.getSystemService(PowerManager::class.java)
        setInteractive(power?.isInteractive != false && !keyguardLocked())
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        context.contentResolver.unregisterContentObserver(autoTimeObserver)
    }

    private fun userIsBack() {
        setInteractive(true)
        recheck()
    }

    private fun setInteractive(interactive: Boolean) {
        if (interactive != lastInteractive) {
            lastInteractive = interactive
            Log.i(TAG, if (interactive) "screen on and unlocked" else "screen off or locked")
        }
        meter.onScreenInteractive(interactive)
        onScreen(interactive)
    }

    private fun keyguardLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private companion object {
        const val TAG = "KryptA11y"
    }
}
