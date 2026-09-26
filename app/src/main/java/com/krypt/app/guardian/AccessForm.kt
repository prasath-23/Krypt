package com.krypt.app.guardian

import com.krypt.app.deeplink.AccessChoice

/**
 * The "Allow for" part of the Guardian's approval screen. The minutes come
 * from a preset chip or a typed value; [choice] is null while a typed value
 * isn't a usable number of minutes, and the approval can't be sent.
 */
data class AccessForm(
    val minutes: Amount = Amount.Preset(AccessChoice.DEFAULT_MINUTES),
) {

    val choice: AccessChoice?
        get() = minutes.value?.takeIf { it in 1..AccessChoice.MAX_MINUTES }?.let(AccessChoice::OneTime)

    /** A typed value that can't be used, shown as an error on the field. */
    val minutesInvalid: Boolean
        get() = minutes is Amount.Custom && minutes.text.isNotEmpty() && choice == null

    sealed interface Amount {
        val value: Int?

        data class Preset(override val value: Int) : Amount

        data class Custom(val text: String) : Amount {
            override val value: Int? get() = text.toIntOrNull()
        }
    }

    companion object {
        /** 15 min, 30 min, 1 hour, 2 hours. */
        val MINUTE_PRESETS = listOf(15, 30, 60, 120)
    }
}
