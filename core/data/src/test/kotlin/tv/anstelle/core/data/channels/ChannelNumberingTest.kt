package tv.anstelle.core.data.channels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelNumberingTest {
    // Productions, Decades, Genres and a group someone made.
    private val starts = linkedMapOf(1L to 1000, 2L to 2000, 3L to 3000, 7L to 5000)

    private fun channel(id: Long, group: Long, name: String, number: Int = 0) = NumberedChannel(id, group, name, number)

    @Test
    fun `each group numbers its channels A to Z from its start`() {
        val channels = listOf(
            channel(1, 3, "Fright Night"),
            channel(2, 3, "case files"),
            channel(3, 3, "Ánime After Dark"),
            channel(4, 1, "Netflix"),
            channel(5, 7, "Saturday Cartoons"),
            channel(6, 7, "Kids"),
        )
        assertEquals(
            mapOf(4L to 1000, 3L to 3000, 2L to 3001, 1L to 3002, 6L to 5000, 5L to 5001),
            ChannelNumbering.numbers(channels, starts),
        )
    }

    @Test
    fun `numbers in names count by value`() {
        val channels = listOf(
            channel(1, 2, "2010s TV"),
            channel(2, 2, "90s TV"),
            channel(3, 2, "2000s TV"),
            channel(4, 2, "50s Movies"),
        )
        assertEquals(mapOf(4L to 2000, 2L to 2001, 3L to 2002, 1L to 2003), ChannelNumbering.numbers(channels, starts))
    }

    @Test
    fun `only changed numbers come back, and numbering again changes nothing`() {
        val channels = listOf(channel(1, 3, "Comedy", number = 3000), channel(2, 3, "Drama", number = 3005))
        assertEquals(mapOf(2L to 3001), ChannelNumbering.numbers(channels, starts))
        val renumbered = channels.map { it.copy(number = ChannelNumbering.numbers(channels, starts)[it.id] ?: it.number) }
        assertEquals(emptyMap(), ChannelNumbering.numbers(renumbered, starts))
    }

    @Test
    fun `overlapping starts still give every channel its own number`() {
        val channels = (1L..30L).map { channel(it, listOf(1L, 2L, 3L, 7L)[(it % 4).toInt()], "Channel $it") }
        val numbers = ChannelNumbering.numbers(channels, starts.mapValues { 10 })
        assertEquals(channels.size, numbers.size)
        assertEquals(numbers.size, numbers.values.toSet().size)
        // The first group by start keeps its run; the next ones go around it.
        assertTrue(numbers.values.all { it in 10 until 40 })
    }

    @Test
    fun `names sort A to Z ignoring case and accents`() {
        val names = listOf("zebra", "Éclair", "apple", "Channel 10", "Channel 9", "channel 9b")
        assertEquals(listOf("apple", "Channel 9", "channel 9b", "Channel 10", "Éclair", "zebra"), names.sortedWith(ChannelNumbering.byName))
    }
}
