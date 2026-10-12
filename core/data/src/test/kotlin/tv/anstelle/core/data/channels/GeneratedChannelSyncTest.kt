package tv.anstelle.core.data.channels

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedChannelSyncTest {
    private fun generated(key: String, name: String = key) = GeneratedChannel(
        key = key, name = name, segment = ChannelGenerator.segmentOf(key)!!, lineup = Lineup.EMPTY,
    ) { MatchResult(emptyList()) }

    @Test
    fun `new channels are added, library channels follow the library, built ones are left alone`() {
        val existing = listOf(
            SyncedChannel(1, "tv:genre:comedy"),
            SyncedChannel(2, "tv:genre:drama"),
            SyncedChannel(3, "tv:tag:isekai"),
            SyncedChannel(4, null),
        )
        val actions = GeneratedChannelSync.plan(
            existing = existing,
            generated = listOf(generated("tv:genre:comedy"), generated("tv:genre:crime")),
            keep = setOf(3L),
        )
        assertEquals(listOf("tv:genre:crime"), actions.add.map { it.key })
        assertEquals(setOf(1L), actions.refresh.keys)
        // Drama is gone with too little left; isekai is gone but still has content.
        assertEquals(listOf(2L), actions.remove)
    }
}
