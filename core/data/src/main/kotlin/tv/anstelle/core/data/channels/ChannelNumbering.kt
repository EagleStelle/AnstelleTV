package tv.anstelle.core.data.channels

import java.text.Collator
import java.util.Locale

/**
 * A block of channel numbers: one of the library's, or one someone made (a group with a start
 * of its own). Every channel is numbered by one.
 */
data class Segment(val key: String, val name: String, val start: Int) {
    companion object {
        /** The key of the segment someone made as group [groupId]. */
        fun keyOf(groupId: Long) = "group:$groupId"
    }
}

/** A channel as numbering sees it: the key of its segment (null for none), its name and number. */
data class NumberedChannel(val id: Long, val segment: String?, val name: String, val number: Int)

/** Gives every channel its number. Pure, so it is tested without a database. */
object ChannelNumbering {
    private val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

    /**
     * A to Z, case and accents ignored, with numbers in names by their value: "90s TV" before
     * "2010s TV", "Channel 9" before "Channel 10".
     */
    val byName: Comparator<String> = Comparator { a, b ->
        val left = chunks(a)
        val right = chunks(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val order = if (x[0].isDigit() && y[0].isDigit()) compareNumbers(x, y) else collator.compare(x, y)
            if (order != 0) return@Comparator order
        }
        left.size.compareTo(right.size)
    }

    /**
     * Every channel's number: each segment's channels count up from its start in [byName] order
     * (1000, 1001, 1002…), segments from the lowest start up, skipping numbers an earlier one
     * gave out, so numbers stay unique even when starts overlap. A channel whose segment isn't in
     * [starts] counts up from [outside]. Returns only the channels whose number changes.
     */
    fun numbers(channels: List<NumberedChannel>, starts: Map<String, Int>, outside: Int): Map<Long, Int> {
        val bySegment = channels.groupBy { channel -> channel.segment?.takeIf { it in starts } }
        // Ties keep the order of [starts]; the channels in no segment come last.
        val blocks = starts.entries.sortedBy { it.value }.map { (key, start) -> start to bySegment[key].orEmpty() } +
            (outside to bySegment[null].orEmpty())
        val order = compareBy(byName) { channel: NumberedChannel -> channel.name }.thenBy { it.id }
        val taken = HashSet<Int>()
        val changes = LinkedHashMap<Long, Int>()
        for ((start, members) in blocks) {
            var next = start
            for (channel in members.sortedWith(order)) {
                while (next in taken) next++
                taken += next
                if (channel.number != next) changes[channel.id] = next
                next++
            }
        }
        return changes
    }

    /** "90s TV" → ["90", "s TV"]: runs of digits and of everything else. */
    private fun chunks(text: String): List<String> {
        val chunks = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val digits = text[i].isDigit()
            val end = (i until text.length).firstOrNull { text[it].isDigit() != digits } ?: text.length
            chunks += text.substring(i, end)
            i = end
        }
        return chunks
    }

    /** Two runs of digits by value, however long they are. */
    private fun compareNumbers(a: String, b: String): Int {
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
    }
}
