package com.krypt.app.guardian

import com.krypt.app.deeplink.AccessChoice
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The "Allow" part of the Guardian's approval screen: one time, repeating
 * (time that comes back every day, or every [period] minutes) for a number
 * of days, or for good. Minutes, days and the period come from a preset chip
 * or a typed value; [choice] is null while a typed value isn't usable, and
 * the approval can't be sent. Repeating, periods shorter than a day, and For
 * good are only offered when the request says the child's phone understands
 * them ([everyDayAllowed], [periodsAllowed], [forGoodAllowed]).
 */
data class AccessForm(
    val kind: Kind = Kind.ONE_TIME,
    val minutes: Amount = Amount.Preset(AccessChoice.DEFAULT_MINUTES),
    val days: Amount = Amount.Preset(AccessChoice.DEFAULT_DAYS),
    /** Repeating only: how often the time comes back. A chip is in minutes; Custom is typed in hours, e.g. "1.5". */
    val every: Amount = Amount.Preset(AccessChoice.DAY_MINUTES),
    val everyDayAllowed: Boolean = false,
    val forGoodAllowed: Boolean = false,
    val periodsAllowed: Boolean = false,
) {

    enum class Kind { ONE_TIME, EVERY_DAY, FOR_GOOD }

    /** The kinds to offer, in order: those the child's phone understands. */
    val kinds: List<Kind>
        get() = buildList {
            add(Kind.ONE_TIME)
            if (everyDayAllowed) add(Kind.EVERY_DAY)
            if (forGoodAllowed) add(Kind.FOR_GOOD)
        }

    /**
     * The minutes in each period of repeating time: a day, unless the child's
     * phone takes shorter ones; null while a typed period can't be used.
     */
    val period: Int?
        get() = when {
            !periodsAllowed -> AccessChoice.DAY_MINUTES
            every is Amount.Custom -> periodMinutes(every.text)
            else -> every.value
        }

    /** A typed period that can't be used, shown as an error on the field. */
    val periodInvalid: Boolean
        get() = kind == Kind.EVERY_DAY && every is Amount.Custom && every.text.isNotEmpty() && period == null

    /** The most minutes the chosen kind allows: a day, or the whole period when repeating. */
    val maxMinutes: Int get() = if (kind == Kind.EVERY_DAY) period ?: AccessChoice.DAY_MINUTES else AccessChoice.MAX_MINUTES

    /**
     * The minute chips to offer: when repeating, those shorter than the
     * period, so its time stays a limit, and smaller ones for an hour or less.
     */
    val minutePresets: List<Int>
        get() {
            val p = period
            if (kind != Kind.EVERY_DAY || p == null) return MINUTE_PRESETS
            return (if (p <= 60) SHORT_MINUTE_PRESETS else MINUTE_PRESETS).filter { it < p }
        }

    val choice: AccessChoice?
        get() = when (kind) {
            // No minutes or days: an unusable typed value can't block it.
            Kind.FOR_GOOD -> if (forGoodAllowed) AccessChoice.ForGood else null
            Kind.ONE_TIME -> usableMinutes?.let { AccessChoice.OneTime(it) }
            Kind.EVERY_DAY -> {
                val m = usableMinutes
                val d = usableDays
                val p = period
                if (everyDayAllowed && m != null && d != null && p != null) AccessChoice.EveryDay(m, d, p) else null
            }
        }

    /**
     * This form with a minutes chip that the kind and period still offer: a
     * chip no longer shown becomes the largest one that is, e.g. 2 hours
     * becomes 30 min when the period is an hour.
     */
    fun fitted(): AccessForm {
        val preset = minutes as? Amount.Preset ?: return this
        if (preset.value in minutePresets) return this
        // No chip is shorter than the period (every 5 minutes): the minutes are typed instead.
        val fit = minutePresets.lastOrNull { it <= preset.value } ?: minutePresets.firstOrNull()
            ?: return copy(minutes = Amount.Custom(""))
        return copy(minutes = Amount.Preset(fit))
    }

    /** A typed number of minutes that can't be used, shown as an error on the field. */
    val minutesInvalid: Boolean
        get() = minutes is Amount.Custom && minutes.text.isNotEmpty() && usableMinutes == null

    /** A typed number of days that can't be used, shown as an error on the field. */
    val daysInvalid: Boolean
        get() = kind == Kind.EVERY_DAY && days is Amount.Custom && days.text.isNotEmpty() && usableDays == null

    private val usableMinutes: Int? get() = minutes.value?.takeIf { it in 1..maxMinutes }
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

        /** For periods of an hour or less: 5, 10, 15, 30 min. */
        val SHORT_MINUTE_PRESETS = listOf(5, 10, 15, 30)

        /** 3, 7, 14, 30 days. */
        val DAY_PRESETS = listOf(3, 7, 14, 30)

        /** Every 30 minutes, 1, 2, 4, 6 or 12 hours, or every day; any other period is typed. */
        val PERIOD_PRESETS = listOf(30, 60, 120, 240, 360, 720, AccessChoice.DAY_MINUTES)

        /**
         * A typed period, in hours, as minutes: "1.5" (or "1,5") and "1:30"
         * are 90, "0.5" is 30, "5" is 300. Null unless it comes to
         * [AccessChoice.MIN_PERIOD_MINUTES] minutes or more, and no more than a day.
         */
        fun periodMinutes(text: String): Int? {
            val typed = text.trim()
            val minutes = if (':' in typed) {
                val parts = typed.split(':')
                val hours = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..24 } ?: return null
                val extra = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 && parts.size == 2 } ?: return null
                hours * 60 + extra
            } else {
                val hours = typed.replace(',', '.').toBigDecimalOrNull() ?: return null
                if (hours.signum() <= 0 || hours > BigDecimal(24)) return null
                hours.multiply(BigDecimal(60)).setScale(0, RoundingMode.HALF_UP).toInt()
            }
            return minutes.takeIf { it in AccessChoice.MIN_PERIOD_MINUTES..AccessChoice.DAY_MINUTES }
        }
    }
}
