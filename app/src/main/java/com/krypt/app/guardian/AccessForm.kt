package com.krypt.app.guardian

import com.krypt.app.deeplink.AccessChoice

/**
 * The "Allow" part of the Guardian's approval screen: one time, every day
 * for a number of days, or for good. Minutes and days come from a preset
 * chip or a typed value; [choice] is null while a typed value isn't usable,
 * and the approval can't be sent. "Every day" and "For good" are only
 * offered when the request says the child's phone understands them
 * ([everyDayAllowed], [forGoodAllowed]).
 */
data class AccessForm(
    val kind: Kind = Kind.ONE_TIME,
    val minutes: Amount = Amount.Preset(AccessChoice.DEFAULT_MINUTES),
    val days: Amount = Amount.Preset(AccessChoice.DEFAULT_DAYS),
    val everyDayAllowed: Boolean = false,
    val forGoodAllowed: Boolean = false,
) {

    enum class Kind { ONE_TIME, EVERY_DAY, FOR_GOOD }

    /** The kinds to offer, in order: those the child's phone understands. */
    val kinds: List<Kind>
        get() = buildList {
            add(Kind.ONE_TIME)
            if (everyDayAllowed) add(Kind.EVERY_DAY)
            if (forGoodAllowed) add(Kind.FOR_GOOD)
        }

    val choice: AccessChoice?
        get() = when (kind) {
            // No minutes or days: an unusable typed value can't block it.
            Kind.FOR_GOOD -> if (forGoodAllowed) AccessChoice.ForGood else null
            Kind.ONE_TIME -> usableMinutes?.let { AccessChoice.OneTime(it) }
            Kind.EVERY_DAY -> {
                val m = usableMinutes
                val d = usableDays
                if (everyDayAllowed && m != null && d != null) AccessChoice.EveryDay(m, d) else null
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
