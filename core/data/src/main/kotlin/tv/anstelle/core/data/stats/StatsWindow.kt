package tv.anstelle.core.data.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** How much the dashboard's chart covers. */
enum class StatsPeriod { DAY, WEEK, MONTH, YEAR }

/**
 * The days the dashboard's lists cover and, inside them, a stretch of time: open on both ends
 * for whole days, one hour when a Day chart's bar is picked.
 */
data class StatsSpan(val fromDay: Long, val toDay: Long, val fromUtcMs: Long = 0, val toUtcMs: Long = OPEN_END) {
    companion object {
        /** Far enough to never clip a row, near enough that adding a duration can't overflow. */
        const val OPEN_END = Long.MAX_VALUE / 4
    }
}

/**
 * A calendar period on the dashboard and what each of its chart bars stands for: the 24 hours of
 * a day, the days of a week or month, or the 12 months of a year. Weeks start on [first], the
 * locale's first day of the week.
 */
data class StatsWindow(val period: StatsPeriod, val first: LocalDate, val last: LocalDate) {
    val firstDay: Long get() = first.toEpochDay()
    val lastDay: Long get() = last.toEpochDay()

    val bars: Int
        get() = when (period) {
            StatsPeriod.DAY -> 24
            StatsPeriod.WEEK -> 7
            StatsPeriod.MONTH -> first.lengthOfMonth()
            StatsPeriod.YEAR -> 12
        }

    operator fun contains(day: Long): Boolean = day in firstDay..lastDay

    /** The first day of [bar]: the window's day for a Day chart, the month's first for a Year. */
    fun dateOf(bar: Int): LocalDate = when (period) {
        StatsPeriod.DAY -> first
        StatsPeriod.WEEK, StatsPeriod.MONTH -> first.plusDays(bar.toLong())
        StatsPeriod.YEAR -> first.plusMonths(bar.toLong())
    }

    /** The bar [day] falls in; -1 outside the window. Day charts' bars are hours, so this is for the others. */
    fun barOf(day: Long): Int = when {
        day !in this -> -1
        period == StatsPeriod.YEAR -> LocalDate.ofEpochDay(day).monthValue - 1
        period == StatsPeriod.DAY -> 0
        else -> (day - firstDay).toInt()
    }

    /** What the lists cover with [bar] picked, or the whole window when none is. */
    fun span(bar: Int?, zone: ZoneId): StatsSpan = when {
        bar == null -> StatsSpan(firstDay, lastDay)
        period == StatsPeriod.DAY -> {
            // An hour the clocks skip is empty; one they repeat runs both times.
            val from = ZonedDateTime.of(first, LocalTime.of(bar, 0), zone).toInstant().toEpochMilli()
            val to = if (bar == 23) LocalDays.startUtcMs(firstDay + 1, zone) else ZonedDateTime.of(first, LocalTime.of(bar + 1, 0), zone).toInstant().toEpochMilli()
            StatsSpan(firstDay, lastDay, from, maxOf(from, to))
        }
        period == StatsPeriod.YEAR -> {
            val month = dateOf(bar)
            StatsSpan(month.toEpochDay(), month.plusMonths(1).toEpochDay() - 1)
        }
        else -> dateOf(bar).toEpochDay().let { StatsSpan(it, it) }
    }

    /** The window [by] periods later (earlier when negative). */
    fun step(by: Int): StatsWindow = when (period) {
        StatsPeriod.DAY -> first.plusDays(by.toLong()).let { StatsWindow(period, it, it) }
        StatsPeriod.WEEK -> first.plusWeeks(by.toLong()).let { StatsWindow(period, it, it.plusDays(6)) }
        StatsPeriod.MONTH -> first.plusMonths(by.toLong()).let { StatsWindow(period, it, it.with(TemporalAdjusters.lastDayOfMonth())) }
        StatsPeriod.YEAR -> first.plusYears(by.toLong()).let { StatsWindow(period, it, it.with(TemporalAdjusters.lastDayOfYear())) }
    }

    companion object {
        /** The [period] holding [day], with weeks starting on [weekStart]. */
        fun of(period: StatsPeriod, day: LocalDate, weekStart: DayOfWeek): StatsWindow = when (period) {
            StatsPeriod.DAY -> StatsWindow(period, day, day)
            StatsPeriod.WEEK -> day.with(TemporalAdjusters.previousOrSame(weekStart)).let { StatsWindow(period, it, it.plusDays(6)) }
            StatsPeriod.MONTH -> StatsWindow(period, day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()))
            StatsPeriod.YEAR -> StatsWindow(period, day.withDayOfYear(1), day.with(TemporalAdjusters.lastDayOfYear()))
        }
    }
}
