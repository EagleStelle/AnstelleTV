package tv.anstelle.core.data.sync

import tv.anstelle.core.database.dao.ItemCollectionsRow
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionChangesTest {
    @Test
    fun `items get their own and their series' box sets, and only changes are written`() {
        val rows = listOf(
            ItemCollectionsRow(1, "movie-1", null, ""),
            ItemCollectionsRow(2, "series-1", null, "|Old Set|"),
            ItemCollectionsRow(3, "episode-1", "series-1", ""),
            ItemCollectionsRow(4, "movie-2", null, "|Saga|"),
            ItemCollectionsRow(5, "movie-3", null, "|Gone|"),
        )
        val members = mapOf(
            "movie-1" to listOf("Saga", "Best Of"),
            "series-1" to listOf("Cartoon Shorts"),
            "episode-1" to listOf("Best Of"),
            "movie-2" to listOf("Saga"),
        )
        assertEquals(
            mapOf(
                1L to "|Best Of|Saga|",
                2L to "|Cartoon Shorts|",
                3L to "|Best Of|Cartoon Shorts|",
                5L to "",
            ),
            collectionChanges(rows, members),
        )
    }
}
