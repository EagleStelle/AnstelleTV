package tv.anstelle.core.data.guide

import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.GuideAiring

/**
 * One channel's guide cells over the loaded hours, as parallel arrays sorted by start time.
 * There is no object per cell, so a guide row lays out and scrolls without allocating.
 * [items] is 0 for an off-air stretch. Immutable once built.
 */
class ProgramStrip(
    val starts: LongArray,
    val ends: LongArray,
    val items: LongArray,
    val titles: Array<String>,
    /** "S1E4 · Pilot", a movie's year, or null. */
    val details: Array<String?>,
) {
    val size: Int get() = starts.size

    fun isOffAir(index: Int): Boolean = items[index] == 0L

    /** The cell airing at [timeMs], or -1. */
    fun indexAt(timeMs: Long): Int {
        val i = firstEndingAfter(timeMs)
        return if (i < size && starts[i] <= timeMs) i else -1
    }

    /** The first cell that ends after [timeMs]; [size] when none does. */
    fun firstEndingAfter(timeMs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ends[mid] <= timeMs) low = mid + 1 else high = mid
        }
        return low
    }

    /** One past the last cell that starts before [timeMs]. */
    fun endOfStartsBefore(timeMs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] < timeMs) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        val EMPTY = ProgramStrip(LongArray(0), LongArray(0), LongArray(0), emptyArray(), emptyArray())

        /** Strips per channel from window rows sorted by channel, then start. */
        fun fromRows(rows: List<GuideAiring>, offAirTitle: String): Map<Long, ProgramStrip> {
            val strips = HashMap<Long, ProgramStrip>()
            var from = 0
            while (from < rows.size) {
                val channelId = rows[from].channelId
                var to = from
                while (to < rows.size && rows[to].channelId == channelId) to++
                val count = to - from
                val starts = LongArray(count)
                val ends = LongArray(count)
                val items = LongArray(count)
                val titles = arrayOfNulls<String>(count)
                val details = arrayOfNulls<String>(count)
                for (i in 0 until count) {
                    val row = rows[from + i]
                    starts[i] = row.startUtcMs
                    ends[i] = row.endUtcMs
                    val title = row.title
                    val item = row.mediaItemId
                    if (item == null || title == null) {
                        titles[i] = offAirTitle
                    } else {
                        items[i] = item
                        titles[i] = programTitle(title, row.showTitle)
                        details[i] = programDetail(title, row.showTitle, row.season, row.episode, row.year)
                    }
                }
                @Suppress("UNCHECKED_CAST")
                strips[channelId] = ProgramStrip(starts, ends, items, titles as Array<String>, details)
                from = to
            }
            return strips
        }

        /**
         * Joins strips of the same channel from several windows into one, in time order. A
         * cell that overlaps two windows appears in both; it is kept once.
         */
        fun merge(parts: List<ProgramStrip>): ProgramStrip {
            val nonEmpty = parts.filter { it.size > 0 }
            if (nonEmpty.isEmpty()) return EMPTY
            if (nonEmpty.size == 1) return nonEmpty[0]
            val ordered = nonEmpty.sortedBy { it.starts[0] }
            val capacity = ordered.sumOf { it.size }
            val starts = LongArray(capacity)
            val ends = LongArray(capacity)
            val items = LongArray(capacity)
            val titles = arrayOfNulls<String>(capacity)
            val details = arrayOfNulls<String>(capacity)
            var n = 0
            for (part in ordered) {
                for (i in 0 until part.size) {
                    // Skip cells already taken from the previous window.
                    if (n > 0 && part.starts[i] < ends[n - 1]) continue
                    starts[n] = part.starts[i]
                    ends[n] = part.ends[i]
                    items[n] = part.items[i]
                    titles[n] = part.titles[i]
                    details[n] = part.details[i]
                    n++
                }
            }
            @Suppress("UNCHECKED_CAST")
            return ProgramStrip(
                starts.copyOf(n),
                ends.copyOf(n),
                items.copyOf(n),
                titles.copyOf(n) as Array<String>,
                details.copyOf(n),
            )
        }
    }
}
