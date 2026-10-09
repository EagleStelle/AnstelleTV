package tv.anstelle.feature.dashboard

import tv.anstelle.core.data.stats.StatsPeriod
import tv.anstelle.core.data.stats.StatsWindow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** "3 h 12 min", "2 h", "45 min"; anything under a minute that isn't nothing reads as 1 min. */
internal fun duration(ms: Long): String {
    val minutes = if (ms in 1 until 60_000) 1 else (ms + 30_000) / 60_000
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$rest min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** "9 PM" or "21:00", as the device tells time. */
internal fun hourName(hour: Int, is24Hour: Boolean): String = when {
    is24Hour -> "%02d:00".format(Locale.ROOT, hour)
    else -> "${(hour + 11) % 12 + 1} ${if (hour < 12) "AM" else "PM"}"
}

internal fun dayCount(days: Int): String = if (days == 1) "1 day" else "$days days"

/** What [window] is: Today, Last week, Oct 5 – 11, September 2026… */
internal fun windowName(window: StatsWindow, today: LocalDate): String {
    val locale = Locale.getDefault()
    val first = window.first
    return when (window.period) {
        StatsPeriod.DAY -> when (first) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> first.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
        }
        StatsPeriod.WEEK -> when {
            today in first..window.last -> "This week"
            today.minusWeeks(1) in first..window.last -> "Last week"
            first.month == window.last.month -> "${first.format(DateTimeFormatter.ofPattern("MMM d", locale))} – ${window.last.dayOfMonth}"
            else -> "${first.format(DateTimeFormatter.ofPattern("MMM d", locale))} – ${window.last.format(DateTimeFormatter.ofPattern("MMM d", locale))}"
        }
        StatsPeriod.MONTH -> when {
            today in first..window.last -> "This month"
            else -> first.format(DateTimeFormatter.ofPattern(if (first.year == today.year) "MMMM" else "MMMM yyyy", locale))
        }
        StatsPeriod.YEAR -> if (first.year == today.year) "This year" else first.year.toString()
    }
}

/** A picked bar, in full: 9 PM, Tue, Oct 7, October. */
internal fun barName(window: StatsWindow, bar: Int, is24Hour: Boolean): String {
    val locale = Locale.getDefault()
    return when (window.period) {
        StatsPeriod.DAY -> hourName(bar, is24Hour)
        StatsPeriod.WEEK, StatsPeriod.MONTH -> window.dateOf(bar).format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
        StatsPeriod.YEAR -> window.dateOf(bar).month.getDisplayName(TextStyle.FULL, locale)
    }
}

/** A bar's mark under the chart: every 6 hours, each weekday, every 7th day, each month. */
internal fun axisName(window: StatsWindow, bar: Int, is24Hour: Boolean): String? {
    val locale = Locale.getDefault()
    return when (window.period) {
        StatsPeriod.DAY -> if (bar % 6 == 0) hourName(bar, is24Hour).removeSuffix(":00") else null
        StatsPeriod.WEEK -> window.dateOf(bar).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        StatsPeriod.MONTH -> if (bar % 7 == 0) (bar + 1).toString() else null
        StatsPeriod.YEAR -> window.dateOf(bar).month.getDisplayName(TextStyle.SHORT, locale)
    }
}

/** The picked bar's own mark, short enough for its slot. */
internal fun axisPick(window: StatsWindow, bar: Int, is24Hour: Boolean): String = when (window.period) {
    StatsPeriod.DAY -> hourName(bar, is24Hour).removeSuffix(":00")
    StatsPeriod.MONTH -> (bar + 1).toString()
    else -> axisName(window, bar, is24Hour).orEmpty()
}

internal fun periodName(period: StatsPeriod): String = when (period) {
    StatsPeriod.DAY -> "Day"
    StatsPeriod.WEEK -> "Week"
    StatsPeriod.MONTH -> "Month"
    StatsPeriod.YEAR -> "Year"
}

/** The chart's scale: a hairline every [stepMs], [count] of them, the top one at or above the tallest bar. */
internal class ChartTicks(val stepMs: Long, val count: Int) {
    val topMs: Long get() = stepMs * count
}

/**
 * Ticks for bars up to [maxMs]: the smallest round step that needs at most [MAX_TICKS] of them
 * (16 hours ticks every 2). Minute steps serve only scales up to an hour, so every figure is
 * whole minutes or whole hours.
 */
internal fun chartTicks(maxMs: Long): ChartTicks {
    if (maxMs <= 0) return ChartTicks(15 * MINUTE_MS, 4)
    for (step in TICK_STEPS) {
        val count = ((maxMs + step - 1) / step).toInt()
        if (count <= MAX_TICKS && (step >= HOUR_MS || step * count <= HOUR_MS)) return ChartTicks(step, count)
    }
    val step = TICK_STEPS.last() * 2
    return ChartTicks(step, ((maxMs + step - 1) / step).toInt())
}

/** A tick's figure: 15 MIN, 2 H, the same voice for both. */
internal fun tickName(ms: Long): String = if (ms < HOUR_MS) "${ms / MINUTE_MS} MIN" else "${ms / HOUR_MS} H"

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val MAX_TICKS = 8
private val TICK_STEPS = longArrayOf(5, 10, 15, 30).map { it * MINUTE_MS } +
    longArrayOf(1, 2, 4, 6, 12, 24, 48, 96, 120, 240).map { it * HOUR_MS }
