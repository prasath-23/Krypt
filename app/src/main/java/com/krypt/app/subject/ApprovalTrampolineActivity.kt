package com.krypt.app.subject

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.krypt.app.R
import com.krypt.app.common.Outcome
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.daily.DailyAccess
import com.krypt.app.data.daily.DailyAllowanceMeter
import com.krypt.app.deeplink.ApprovalConsumer
import com.krypt.app.deeplink.ApprovalError
import com.krypt.app.deeplink.ApprovalOutcome
import com.krypt.app.ui.common.dayText
import com.krypt.app.ui.common.durationText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

/**
 * Amendment 1 WP22: silent Subject-side consumer of `krypt://approve?...`
 * deep-links.
 *
 * Presents ZERO interactive surface (FR-018). The moment the URL arrives
 * we call [ApprovalConsumer.consume]. On success we record the grant, or
 * hand the every-day rule to the meter (either closes the app's lock
 * screen), play a 500 ms green-flash + haptic + toast, open the now-unlocked
 * app, and finish. An every-day approval opens the app only if today has
 * time left; otherwise a toast says today's time is used up. On error we
 * show a brief toast and finish. No PIN keypad, no buttons, no text fields.
 *
 * Registered in the manifest with [Theme.Krypt.Trampoline] (translucent)
 * so no white window frame is ever visible.
 */
@AndroidEntryPoint
class ApprovalTrampolineActivity : ComponentActivity() {

    @Inject lateinit var consumer: ApprovalConsumer
    @Inject lateinit var successEffect: UnlockSuccessEffect
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var dailyMeter: DailyAllowanceMeter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_approval_trampoline)

        val url = intent?.dataString
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.approval_error_unreadable, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Single-use Activity: if the user taps the same link again while a
        // consumption is in flight, Android's singleTask launchMode re-enters
        // onNewIntent instead of onCreate; we handle that by just closing and
        // letting the next tap re-open.
        lifecycleScope.launch {
            when (val outcome = consumer.consume(url)) {
                is Outcome.Ok  -> onUnlockSuccess(outcome.value)
                is Outcome.Err -> onUnlockError(outcome.error)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Ignore re-entries; the first consume call is already running or done.
        // The next tap will create a new Activity instance.
    }

    private suspend fun onUnlockSuccess(outcome: ApprovalOutcome) {
        val label = appLabel(outcome.targetPackage)
        when (outcome) {
            is ApprovalOutcome.OneTime -> {
                sessionStore.recordGrant(outcome.targetPackage, outcome.grantExpiresAtMs)
                val until = DateFormat.getTimeFormat(this).format(Date(outcome.grantExpiresAtMs))
                successEffect.play(this, getString(R.string.approval_toast_unlocked, label, until))
                openUnlockedApp(outcome.targetPackage)
            }
            is ApprovalOutcome.EveryDay -> {
                // Before the app opens, so the accessibility service already knows the rule.
                val rule = outcome.allowance
                dailyMeter.onRuleSaved(rule)
                if (dailyMeter.access(rule.packageName) is DailyAccess.Available) {
                    val until = dayText(this, rule.lastDay, rule.zone)
                    val perDay = durationText(this, rule.minutesPerDay)
                    successEffect.play(this, getString(R.string.approval_toast_daily, label, perDay, until))
                    openUnlockedApp(outcome.targetPackage)
                } else {
                    Toast.makeText(this, getString(R.string.approval_toast_daily_used_up, label), Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun openUnlockedApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Uninstalled or disabled since the request; the grant still stands.
        }
    }

    private fun onUnlockError(error: ApprovalError) {
        Toast.makeText(this, messageFor(error), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun messageFor(error: ApprovalError): Int = when (error) {
        ApprovalError.BadScheme,
        ApprovalError.WrongVersion,
        is ApprovalError.MissingParam,
        ApprovalError.BadBase64,
        ApprovalError.BadUuid,
        -> R.string.approval_error_unreadable
        ApprovalError.UnmatchedRequest -> R.string.approval_error_unmatched
        ApprovalError.RequestExpired -> R.string.approval_error_expired
        ApprovalError.CipherDecryptFailed,
        ApprovalError.PayloadInconsistent,
        -> R.string.approval_error_tamper
        ApprovalError.NotPaired -> R.string.approval_error_not_configured
        ApprovalError.ClockNotTrusted -> R.string.approval_error_needs_auto_time
    }
}
