package tv.anstelle.core.data.channels

import tv.anstelle.core.data.item
import tv.anstelle.core.data.seriesRow
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import kotlin.test.Test
import kotlin.test.assertEquals

class LineupChoicesTest {
    private val choices = LineupChoices(
        Catalog(
            listOf(
                seriesRow(900, "s-office", "The Example Office"),
                item(1, series = "s-office", genres = listOf("comedy", "Sitcom"), year = 2005, rating = "TV-14", score = 14),
                item(2, series = "s-office", genres = listOf("Comedy"), year = 2006, rating = "PG-13", score = 14),
                item(3, type = MediaType.MOVIE, title = "Zebra Days", genres = listOf("Drama"), year = 1983, rating = "PG", score = 10),
                item(4, type = MediaType.MOVIE, title = "apple Pie", genres = listOf("Drama"), year = null, rating = "R", score = 17),
            ),
        ),
    )

    private fun labels(field: RuleField, op: RuleOp = RuleOp.ANY_OF, query: String = "", from: String? = null) =
        choices.options(field, op, query, from = from).map { it.label }

    @Test
    fun `values list alphabetically, whatever is picked`() {
        assertEquals(listOf("comedy", "Drama", "Sitcom"), labels(RuleField.GENRE))
        assertEquals(listOf("apple Pie", "Zebra Days (1983)"), labels(RuleField.ITEM))
        assertEquals(listOf("The Example Office"), labels(RuleField.SERIES))
        assertEquals(listOf("episodes", "movies"), labels(RuleField.MEDIA_TYPE))
    }

    @Test
    fun `years and rating scores list ascending`() {
        assertEquals(listOf("1983", "2005", "2006"), labels(RuleField.YEAR, RuleOp.BETWEEN))
        // A score is named by the ratings the library gives it.
        assertEquals(listOf("PG", "PG-13, TV-14", "R"), labels(RuleField.OFFICIAL_RATING, RuleOp.AT_MOST))
        assertEquals("PG-13, TV-14", choices.ratingOf("14"))
    }

    @Test
    fun `a range's end lists only values from its start on`() {
        assertEquals(listOf("2005", "2006"), labels(RuleField.YEAR, RuleOp.BETWEEN, from = "2005"))
    }

    @Test
    fun `a search keeps what is picked`() {
        assertEquals(listOf("Drama"), labels(RuleField.GENRE, query = "dra"))
        val kept = choices.options(RuleField.GENRE, RuleOp.ANY_OF, "dra", selected = setOf("Sitcom")).map { it.label }
        assertEquals(listOf("Drama", "Sitcom"), kept)
    }

    @Test
    fun `rules read in the builder's words`() {
        assertEquals("Rating is at most PG-13, TV-14", choices.describe(Rule(RuleField.OFFICIAL_RATING, RuleOp.AT_MOST, "14")))
        assertEquals("Series is any of The Example Office", choices.describe(Rule(RuleField.SERIES, RuleOp.ANY_OF, "s-office")))
        val reach = choices.reach(Lineup(listOf(RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Comedy"))))))
        assertEquals(2, reach.episodes)
        assertEquals(1, reach.series)
    }

    @Test
    fun `long lists stop at the cap`() {
        val many = LineupChoices(Catalog(List(LineupChoices.MAX_OPTIONS + 50) { item(it + 1L, type = MediaType.MOVIE, title = "Movie ${it + 1}") }))
        assertEquals(LineupChoices.MAX_OPTIONS, many.options(RuleField.ITEM, RuleOp.ANY_OF).size)
    }
}
