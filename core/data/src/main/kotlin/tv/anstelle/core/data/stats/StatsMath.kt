package tv.anstelle.core.data.stats

import java.time.Instant
import java.time.ZoneId

/** The current run of days watched in a row (alive through yesterday) and the longest ever. */
data class Streaks(val current: Int, val best: Int)

/** The dashboard's arithmetic over query results: no I/O, so it is unit-tested on the JVM. */
object StatsMath {
    /** Views closer together than this are one sitting. */
    const val SITTING_GAP_MS = 5 * 60_000L

    private const val HOUR_MS = 3_600_000L

    /**
     * Adds [durationMs] from [startUtcMs] onward to the local hours of the day it ran through, in
     * [zone]: [hours] has 24 slots, midnight first. A local hour that happens twice when the
     * clocks go back adds both to its slot.
     */
    fun addHours(startUtcMs: Long, durationMs: Long, zone: ZoneId, hours: LongArray) {
        var from = startUtcMs
        val to = startUtcMs + durationMs
        val rules = zone.rules
        while (from < to) {
            val offsetMs = rules.getOffset(Instant.ofEpochMilli(from)).totalSeconds * 1000L
            val local = from + offsetMs
            val hour = Math.floorMod(Math.floorDiv(local, HOUR_MS), 24L).toInt()
            // Clock changes fall on a local hour's edge, so the next edge comes at the old offset.
            val end = minOf(to, (Math.floorDiv(local, HOUR_MS) + 1) * HOUR_MS - offsetMs)
            hours[hour] += end - from
            from = end
        }
    }

    /** Runs of consecutive [days] (epoch days, ascending, no repeats); the current one ends today or yesterday. */
    fun streaks(days: List<Long>, today: Long): Streaks {
        var best = 0
        var run = 0
        var previous = Long.MIN_VALUE
        for (day in days) {
            run = if (previous != Long.MIN_VALUE && day == previous + 1) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        val current = if (previous == today || previous == today - 1) run else 0
        return Streaks(current, best)
    }

    /**
     * The longest sitting among views sorted by start: views less than [SITTING_GAP_MS] apart run
     * together, from the first one's start to the end of the last.
     */
    fun longestSitting(starts: LongArray, durations: LongArray, count: Int = starts.size): Long {
        var longest = 0L
        var i = 0
        while (i < count) {
            val first = starts[i]
            var end = first + durations[i]
            i++
            while (i < count && starts[i] - end < SITTING_GAP_MS) {
                end = maxOf(end, starts[i] + durations[i])
                i++
            }
            longest = maxOf(longest, end - first)
        }
        return longest
    }

    /** The hour of day (0–23) with the most time in [hours], or null when nothing was watched. */
    fun peakHour(hours: LongArray): Int? {
        var peak = -1
        for (h in hours.indices) if (hours[h] > 0 && (peak < 0 || hours[h] > hours[peak])) peak = h
        return peak.takeIf { it >= 0 }
    }
}
