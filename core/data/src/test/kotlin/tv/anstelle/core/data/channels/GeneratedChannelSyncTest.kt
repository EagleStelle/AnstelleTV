package tv.anstelle.core.data.channels

import tv.anstelle.core.model.PlaybackOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedChannelSyncTest {
    private val order = ChannelGenerator(ChannelWords.EMPTY).order

    private fun generated(key: String, name: String = key) = GeneratedChannel(
        key = key, name = name, segment = ChannelGenerator.segmentOf(key)!!, lineup = Lineup.EMPTY,
        order = PlaybackOrder.EPISODE_ORDER,
    ) { MatchResult(emptyList()) }

    private fun channel(id: Long, key: String?, managed: Boolean = key != null, number: Int = id.toInt(), name: String = key ?: "Custom $id") =
        SyncedChannel(id, key, managed, number, name)

    @Test
    fun `new channels are added, managed ones follow the library, edited and deleted ones are left alone`() {
        val existing = listOf(
            channel(1, "tv:genre:comedy"),
            channel(2, "tv:genre:drama"),
            channel(3, "tv:tag:isekai"),
            channel(4, "tv:studio:netflix", managed = false),
            channel(5, null),
        )
        val actions = GeneratedChannelSync.plan(
            existing = existing,
            generated = listOf(generated("tv:genre:comedy"), generated("tv:genre:crime"), generated("tv:studio:netflix"), generated("tv:tag:zombies")),
            dismissed = setOf("tv:tag:zombies"),
            keep = setOf(3L),
        )
        // Crime is new; zombies was deleted; netflix is held by an edited channel.
        assertEquals(listOf("tv:genre:crime"), actions.add.map { it.key })
        assertEquals(setOf(1L), actions.refresh.keys)
        // Drama is gone with too little left; isekai is gone but still has content.
        assertEquals(listOf(2L), actions.remove)
    }

    @Test
    fun `each segment numbers its managed channels from its start around the numbers others hold`() {
        val channels = listOf(
            channel(1, "tv:studio:netflix", number = 1, name = "Netflix"),
            channel(2, "tv:genre:drama", number = 9, name = "Drama"),
            channel(3, "tv:genre:comedy", number = 3, name = "Comedy"),
            channel(4, null, number = 3001),
            channel(5, "tv:decade:2010", number = 5, name = "2010s TV"),
            channel(6, "tv:decade:1990", number = 6, name = "90s TV"),
            channel(7, "tv:tag:isekai", managed = false, number = 4000, name = "Isekai"),
            channel(8, "movies:studio:netflix", number = 8, name = "Netflix Movies"),
        )
        val start = { segment: ChannelSegment -> segment.defaultStart }
        // Productions from 1000, decades from 2000, genres from 3000 (3001 is taken).
        assertEquals(
            mapOf(1L to 1000, 8L to 1001, 6L to 2000, 5L to 2001, 3L to 3000, 2L to 3002),
            GeneratedChannelSync.numbers(channels, order, start),
        )
        val renumbered = channels.map { it.copy(number = GeneratedChannelSync.numbers(channels, order, start)[it.id] ?: it.number) }
        assertEquals(emptyMap(), GeneratedChannelSync.numbers(renumbered, order, start))
        // Starts that overlap still give every channel its own number.
        val numbers = GeneratedChannelSync.numbers(channels, order) { 10 }.values
        assertEquals(numbers.size, numbers.toSet().size)
    }
}
