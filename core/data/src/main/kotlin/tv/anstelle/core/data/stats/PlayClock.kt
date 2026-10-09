package tv.anstelle.core.data.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Local calendar days as epoch days, the dashboard's unit. */
object LocalDays {
    private const val SECONDS_PER_DAY = 86_400L

    /** The epoch day [utcMs] falls on in [zone]. */
    fun of(utcMs: Long, zone: ZoneId): Long {
        val seconds = Math.floorDiv(utcMs, 1000L)
        val offset = zone.rules.getOffset(Instant.ofEpochSecond(seconds)).totalSeconds
        return Math.floorDiv(seconds + offset, SECONDS_PER_DAY)
    }

    /** When [day] starts in [zone]; a day that skips midnight starts at its first hour. */
    fun startUtcMs(day: Long, zone: ZoneId): Long = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** What a view played on one local day: its first moment that day and the time played. */
data class DayShare(val localDay: Long, val startUtcMs: Long, val playedMs: Long)

/**
 * The stretches of a view that actually played, kept from the player's play and pause edges:
 * one timestamp per change and nothing ticking, so pauses and buffering never count. Main
 * thread only.
 */
class PlayClock {
    private var since = NOT_PLAYING
    private var stretches = LongArray(8)
    private var size = 0

    /** The player started or stopped playing at [atUtcMs]. */
    fun set(playing: Boolean, atUtcMs: Long) {
        if (playing) {
            if (since == NOT_PLAYING) since = atUtcMs
        } else if (since != NOT_PLAYING) {
            add(since, atUtcMs)
            since = NOT_PLAYING
        }
    }

    /** The view ended at [atUtcMs]. */
    fun stop(atUtcMs: Long) = set(false, atUtcMs)

    val playedMs: Long
        get() {
            var total = 0L
            for (i in 0 until size step 2) total += stretches[i + 1] - stretches[i]
            return total
        }

    /** The time played on each local day in [zone], split at each local midnight. */
    fun byDay(zone: ZoneId): List<DayShare> {
        val shares = ArrayList<DayShare>(1)
        for (i in 0 until size step 2) {
            var from = stretches[i]
            val to = stretches[i + 1]
            while (from < to) {
                val day = LocalDays.of(from, zone)
                val end = minOf(to, LocalDays.startUtcMs(day + 1, zone))
                val last = shares.lastOrNull()
                if (last?.localDay == day) {
                    shares[shares.lastIndex] = last.copy(playedMs = last.playedMs + end - from)
                } else {
                    shares += DayShare(day, from, end - from)
                }
                from = end
            }
        }
        return shares
    }

    private fun add(from: Long, to: Long) {
        if (to <= from) return
        // A stretch that picks up where the last one ended extends it.
        if (size > 0 && stretches[size - 1] == from) {
            stretches[size - 1] = to
            return
        }
        if (size == stretches.size) stretches = stretches.copyOf(size * 2)
        stretches[size++] = from
        stretches[size++] = to
    }

    private companion object {
        const val NOT_PLAYING = Long.MIN_VALUE
    }
}
