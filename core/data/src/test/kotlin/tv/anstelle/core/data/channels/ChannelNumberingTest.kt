package tv.anstelle.core.data.channels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelNumberingTest {
    private val starts = linkedMapOf("productions" to 1000, "decades" to 2000, "genres" to 3000, "group:7" to 5000)

    private fun channel(id: Long, segment: String?, name: String, number: Int = 0) = NumberedChannel(id, segment, name, number)

    @Test
    fun `each segment numbers its channels A to Z from its start`() {
        val channels = listOf(
            channel(1, "genres", "Fright Night"),
            channel(2, "genres", "case files"),
            channel(3, "genres", "Ánime After Dark"),
            channel(4, "productions", "Netflix"),
            channel(5, "group:7", "Saturday Cartoons"),
            channel(6, "group:7", "Kids"),
        )
        assertEquals(
            mapOf(4L to 1000, 3L to 3000, 2L to 3001, 1L to 3002, 6L to 5000, 5L to 5001),
            ChannelNumbering.numbers(channels, starts, outside = 6000),
        )
    }

    @Test
    fun `numbers in names count by value`() {
        val channels = listOf(
            channel(1, "decades", "2010s TV"),
            channel(2, "decades", "90s TV"),
            channel(3, "decades", "2000s TV"),
            channel(4, "decades", "50s Movies"),
        )
        assertEquals(mapOf(4L to 2000, 2L to 2001, 3L to 2002, 1L to 2003), ChannelNumbering.numbers(channels, starts, outside = 6000))
    }

    @Test
    fun `a channel in no segment, or one that is gone, counts from outside`() {
        val channels = listOf(channel(1, null, "Mine"), channel(2, "group:99", "Another"), channel(3, "genres", "Comedy"))
        assertEquals(mapOf(2L to 6000, 1L to 6001, 3L to 3000), ChannelNumbering.numbers(channels, starts, outside = 6000))
    }

    @Test
    fun `only changed numbers come back, and numbering again changes nothing`() {
        val channels = listOf(channel(1, "genres", "Comedy", number = 3000), channel(2, "genres", "Drama", number = 3005))
        assertEquals(mapOf(2L to 3001), ChannelNumbering.numbers(channels, starts, outside = 6000))
        val renumbered = channels.map { it.copy(number = ChannelNumbering.numbers(channels, starts, 6000)[it.id] ?: it.number) }
        assertEquals(emptyMap(), ChannelNumbering.numbers(renumbered, starts, outside = 6000))
    }

    @Test
    fun `overlapping starts still give every channel its own number`() {
        val channels = (1L..30L).map { channel(it, listOf("productions", "decades", "genres", "group:7", null)[(it % 5).toInt()], "Channel $it") }
        val numbers = ChannelNumbering.numbers(channels, starts.mapValues { 10 }, outside = 10)
        assertEquals(channels.size, numbers.size)
        assertEquals(numbers.size, numbers.values.toSet().size)
        // The first segment by start keeps its run; the next ones go around it.
        assertTrue(numbers.values.all { it in 10 until 40 })
    }

    @Test
    fun `names sort A to Z ignoring case and accents`() {
        val names = listOf("zebra", "Éclair", "apple", "Channel 10", "Channel 9", "channel 9b")
        assertEquals(listOf("apple", "Channel 9", "channel 9b", "Channel 10", "Éclair", "zebra"), names.sortedWith(ChannelNumbering.byName))
    }
}
