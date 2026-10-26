package com.krypt.app.data.daily

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The Guardian's every-day rule for one app: up to [minutes] minutes in each
 * [periodMinutes]-minute period of each calendar day from [firstDay] through
 * [lastDay]. Periods follow the clock from midnight, like the day: every 90
 * minutes is 00:00-01:30, 01:30-03:00, ... When the period doesn't divide a
 * day, the day's last period is shorter and ends at midnight (every 5 hours:
 * ..., 15:00-20:00, 20:00-24:00). With [DAY_MINUTES] the period is the day
 * itself, "[minutes] a day". Days and minutes are counted in [zone] (the
 * device's zone when the rule was approved, so changing the time zone can't
 * make extra days).
 */
data class DailyAllowance(
    val packageName: String,
    /** Minutes the app may be used in each period. */
    val minutes: Int,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val zone: ZoneId,
    val requestId: UUID,
    val createdAtMs: Long,
    /** Minutes in a period, up to [DAY_MINUTES]. */
    val periodMinutes: Int = DAY_MINUTES,
) {
    val allowedMs: Long get() = minutes * 60_000L

    fun covers(day: LocalDate): Boolean = !day.isBefore(firstDay) && !day.isAfter(lastDay)

    /** The minutes ([LocalMinutes]) of the period that [minute] is in. */
    fun periodOf(minute: Long): LongRange {
        val dayStart = minute - Math.floorMod(minute, LocalMinutes.PER_DAY.toLong())
        val start = dayStart + periodStartMinute((minute - dayStart).toInt())
        return start until minOf(start + periodMinutes, dayStart + LocalMinutes.PER_DAY)
    }

    /** When the period that [epochMs] falls in started: when the clock first showed its start. Never after [epochMs]. */
    fun periodStartMs(epochMs: Long): Long {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        val start = local.toLocalDate().atStartOfDay().plusMinutes(periodStartMinute(local.hour * 60 + local.minute))
        return minOf(start.atZone(zone).toInstant().toEpochMilli(), epochMs)
    }

    /**
     * When the period that [epochMs] falls in ends, which is when the next
     * one starts. Always after [epochMs]. A clock change ends it sooner when
     * the clock jumps into another period: forward past the period's end, or
     * back into an earlier one.
     */
    fun periodEndMs(epochMs: Long): Long {
        val instant = Instant.ofEpochMilli(epochMs)
        val local = instant.atZone(zone)
        val endMinute = minOf(periodStartMinute(local.hour * 60 + local.minute) + periodMinutes, LocalMinutes.PER_DAY.toLong())
        val onTheClock = LocalMinutes.firstAfter(local.toLocalDate().atStartOfDay().plusMinutes(endMinute), zone, epochMs)
        val change = zone.rules.nextTransition(instant)?.instant?.toEpochMilli()
        val jumpsOut = change != null && change > epochMs && change < onTheClock &&
            LocalMinutes.of(change, zone) !in periodOf(LocalMinutes.of(epochMs, zone))
        return if (jumpsOut) change!! else onTheClock
    }

    /** The minute of the day that the period holding [minuteOfDay] starts at. */
    private fun periodStartMinute(minuteOfDay: Int): Long = (minuteOfDay - minuteOfDay % periodMinutes).toLong()

    companion object {
        /** A rule's period when it is the whole day: "minutes a day". */
        const val DAY_MINUTES = 24 * 60
    }
}

/**
 * Minutes on the clock, the unit every-day rules count time in: minutes
 * since the first midnight of the epoch in a zone, that is epoch day * 1440 +
 * the minute of the day. A minute that happens twice, when summer time ends,
 * is one minute here.
 */
internal object LocalMinutes {

    /** How many a day has. */
    const val PER_DAY = 24 * 60

    fun of(epochMs: Long, zone: ZoneId): Long {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        return local.toLocalDate().toEpochDay() * PER_DAY + local.hour * 60 + local.minute
    }

    fun day(minute: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(minute, PER_DAY.toLong()))

    /**
     * When the minute that [epochMs] is in ends; always after [epochMs]. A
     * change of the zone's offset ends it too, since the clock jumps there.
     */
    fun nextStartMs(epochMs: Long, zone: ZoneId): Long {
        val instant = Instant.ofEpochMilli(epochMs)
        val next = instant.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val onTheClock = firstAfter(next, zone, epochMs)
        val change = zone.rules.nextTransition(instant)?.instant?.toEpochMilli()
        return if (change != null && change > epochMs && change < onTheClock) change else onTheClock
    }

    /**
     * The first instant after [afterMs] at which the clock in [zone] shows
     * [time]. If the clock skips it (summer time starting), the instant the
     * clock jumps past it.
     */
    fun firstAfter(time: LocalDateTime, zone: ZoneId, afterMs: Long): Long {
        val shown = zone.rules.getValidOffsets(time)
            .map { time.toInstant(it).toEpochMilli() }
            .filter { it > afterMs }
        return shown.minOrNull() ?: maxOf(time.atZone(zone).toInstant().toEpochMilli(), afterMs + 1)
    }
}

/** Where an app's every-day rule stands right now. */
sealed interface DailyAccess {
    /** No rule covers today. */
    data object None : DailyAccess

    /** The app may be used for [remainingMs] more in this period. */
    data class Available(
        val remainingMs: Long,
        val minutes: Int,
        val lastDay: LocalDate,
        val periodMinutes: Int = DailyAllowance.DAY_MINUTES,
    ) : DailyAccess

    /**
     * This period's time is used up. It comes back when the next period
     * starts: tomorrow for a day, or at [backAtMs] (milliseconds since the
     * epoch). [backAtMs] is null for a day, and when the rule ends before the
     * next period.
     */
    data class UsedUp(
        val minutes: Int,
        val lastDay: LocalDate,
        val periodMinutes: Int = DailyAllowance.DAY_MINUTES,
        val backAtMs: Long? = null,
    ) : DailyAccess

    /** Automatic date & time is off, so the period can't be told; the app stays locked. */
    data class Paused(
        val minutes: Int,
        val lastDay: LocalDate,
        val periodMinutes: Int = DailyAllowance.DAY_MINUTES,
    ) : DailyAccess
}

/** What screens read about every-day rules, and what they can do with them. */
interface DailyAllowances {
    /** Changes whenever what [access] returns may have changed. */
    val changes: StateFlow<Long>

    fun access(pkg: String): DailyAccess

    /** End [pkg]'s rule now; the app asks every time again. */
    suspend fun end(pkg: String)
}
