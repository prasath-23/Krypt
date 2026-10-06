package com.krypt.app.guardian

import com.krypt.app.deeplink.AccessChoice

/**
 * The "Allow" part of the Guardian's approval screen: one time, or every
 * day for a number of days. Minutes and days come from a preset chip or a
 * typed value; [choice] is null while a typed value isn't usable, and the
 * approval can't be sent. "Every day" is only offered when the request says
 * the child's phone understands it ([everyDayAllowed]).
 */
data class AccessForm(
    val kind: Kind = Kind.ONE_TIME,
    val minutes: Amount = Amount.Preset(AccessChoice.DEFAULT_MINUTES),
    val days: Amount = Amount.Preset(AccessChoice.DEFAULT_DAYS),
    val everyDayAllowed: Boolean = false,
) {

    enum class Kind { ONE_TIME, EVERY_DAY }

    val choice: AccessChoice?
        get() {
            val m = usableMinutes ?: return null
            return when (kind) {
                Kind.ONE_TIME -> AccessChoice.OneTime(m)
                Kind.EVERY_DAY -> {
                    val d = usableDays ?: return null
                    if (everyDayAllowed) AccessChoice.EveryDay(m, d) else null
                }
            }
        }

    /** A typed number of minutes that can't be used, shown as an error on the field. */
    val minutesInvalid: Boolean
        get() = minutes is Amount.Custom && minutes.text.isNotEmpty() && usableMinutes == null

    /** A typed number of days that can't be used, shown as an error on the field. */
    val daysInvalid: Boolean
        get() = kind == Kind.EVERY_DAY && days is Amount.Custom && days.text.isNotEmpty() && usableDays == null

    private val usableMinutes: Int? get() = minutes.value?.takeIf { it in 1..AccessChoice.MAX_MINUTES }
    private val usableDays: Int? get() = days.value?.takeIf { it in 1..AccessChoice.MAX_DAYS }

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

        /** 3, 7, 14, 30 days. */
        val DAY_PRESETS = listOf(3, 7, 14, 30)
    }
}
