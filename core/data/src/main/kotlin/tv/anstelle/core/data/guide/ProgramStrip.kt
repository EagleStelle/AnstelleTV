package tv.anstelle.core.data.guide

import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.GuideAiring
import tv.anstelle.core.model.ProfileAccess

/**
 * One channel's guide cells over the loaded hours, as parallel arrays sorted by start time.
 * There is no object per cell, so a guide row lays out and scrolls without allocating.
 * [items] and [shows] are 0 for an off-air stretch. Immutable once built, and the same for
 * every profile: [isRestricted] applies what a profile may watch.
 */
class ProgramStrip(
    val starts: LongArray,
    val ends: LongArray,
    val items: LongArray,
    /** The episode's series, or the item itself when it has none. */
    val shows: LongArray,
    val titles: Array<String>,
    /** "S1E4 - Pilot", a movie's year, or null. */
    val details: Array<String?>,
    /** Each program's rating score; [UNRATED] for none and off air. */
    val scores: IntArray,
) {
    val size: Int get() = starts.size

    fun isOffAir(index: Int): Boolean = items[index] == 0L

    /**
     * True when a profile with [access] may not watch the program: it is over the profile's
     * limit (unrated programs never pass one), or the profile's Jellyfin user can't see it.
     */
    fun isRestricted(index: Int, access: ProfileAccess): Boolean {
        if (access.isOpen || isOffAir(index)) return false
        val score = scores[index]
        return !access.allows(items[index], if (score == UNRATED) null else score)
    }

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

    /**
     * A color out of [count] for each cell, -1 off air: a show's back-to-back airings share
     * one, and the next show takes another. A show's color comes from its id, moved on by one
     * when the show before it has it, so loading more hours rarely recolors a cell.
     */
    fun runSlots(count: Int): IntArray {
        val slots = IntArray(size)
        for (i in 0 until size) {
            val before = if (i > 0) slots[i - 1] else -1
            slots[i] = when {
                isOffAir(i) -> -1
                before >= 0 && shows[i] == shows[i - 1] -> before
                else -> {
                    val slot = ((shows[i] * SPREAD) ushr 33).toInt() % count
                    if (slot == before) (slot + 1) % count else slot
                }
            }
        }
        return slots
    }

    /**
     * This strip for a profile with [access]: back-to-back programs the profile may not watch
     * become one cell spanning them all, taking the first one's item, title and rating, so the
     * guide shows one blacked-out stretch and when it ends. The same strip when nothing is kept
     * from the profile, or nothing restricted is next to each other.
     */
    fun mergeRestricted(access: ProfileAccess): ProgramStrip {
        if (access.isOpen || size < 2) return this
        var merges = false
        for (i in 1 until size) {
            if (joins(i, access)) {
                merges = true
                break
            }
        }
        if (!merges) return this
        val keep = BooleanArray(size) { i -> i == 0 || !joins(i, access) }
        val count = keep.count { it }
        val newStarts = LongArray(count)
        val newEnds = LongArray(count)
        val newItems = LongArray(count)
        val newShows = LongArray(count)
        val newTitles = arrayOfNulls<String>(count)
        val newDetails = arrayOfNulls<String>(count)
        val newScores = IntArray(count)
        var n = -1
        for (i in 0 until size) {
            if (keep[i]) {
                n++
                newStarts[n] = starts[i]
                newItems[n] = items[i]
                newShows[n] = shows[i]
                newTitles[n] = titles[i]
                newDetails[n] = details[i]
                newScores[n] = scores[i]
            }
            newEnds[n] = ends[i]
        }
        @Suppress("UNCHECKED_CAST")
        return ProgramStrip(newStarts, newEnds, newItems, newShows, newTitles as Array<String>, newDetails, newScores)
    }

    /** Cell [i] carries on the restricted stretch the cell before it is part of. */
    private fun joins(i: Int, access: ProfileAccess): Boolean = isRestricted(i, access) && isRestricted(i - 1, access) && starts[i] == ends[i - 1]

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
        val EMPTY = ProgramStrip(LongArray(0), LongArray(0), LongArray(0), LongArray(0), emptyArray(), emptyArray(), IntArray(0))

        const val UNRATED = -1

        /** Spreads consecutive ids over the colors (the golden-ratio multiplier). */
        private const val SPREAD = -7046029254386353131L

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
                val shows = LongArray(count)
                val titles = arrayOfNulls<String>(count)
                val details = arrayOfNulls<String>(count)
                val scores = IntArray(count) { UNRATED }
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
                        shows[i] = row.showId ?: item
                        titles[i] = programTitle(title, row.showTitle)
                        details[i] = programDetail(title, row.showTitle, row.season, row.episode, row.year)
                        scores[i] = row.ratingScore ?: UNRATED
                    }
                }
                @Suppress("UNCHECKED_CAST")
                strips[channelId] = ProgramStrip(starts, ends, items, shows, titles as Array<String>, details, scores)
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
            val shows = LongArray(capacity)
            val titles = arrayOfNulls<String>(capacity)
            val details = arrayOfNulls<String>(capacity)
            val scores = IntArray(capacity)
            var n = 0
            for (part in ordered) {
                for (i in 0 until part.size) {
                    // Skip cells already taken from the previous window.
                    if (n > 0 && part.starts[i] < ends[n - 1]) continue
                    starts[n] = part.starts[i]
                    ends[n] = part.ends[i]
                    items[n] = part.items[i]
                    shows[n] = part.shows[i]
                    titles[n] = part.titles[i]
                    details[n] = part.details[i]
                    scores[n] = part.scores[i]
                    n++
                }
            }
            @Suppress("UNCHECKED_CAST")
            return ProgramStrip(
                starts.copyOf(n),
                ends.copyOf(n),
                items.copyOf(n),
                shows.copyOf(n),
                titles.copyOf(n) as Array<String>,
                details.copyOf(n),
                scores.copyOf(n),
            )
        }
    }
}
