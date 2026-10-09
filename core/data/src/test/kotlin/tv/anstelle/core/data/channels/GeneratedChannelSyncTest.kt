package tv.anstelle.core.data.channels

import tv.anstelle.core.model.PlaybackOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedChannelSyncTest {
    private fun generated(key: String, name: String = key) = GeneratedChannel(
        key = key, name = name, segment = ChannelGenerator.segmentOf(key)!!, lineup = Lineup.EMPTY,
        order = PlaybackOrder.EPISODE_ORDER,
    ) { MatchResult(emptyList()) }

    private fun channel(id: Long, key: String?, managed: Boolean = key != null) = SyncedChannel(id, key, managed)

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
}
