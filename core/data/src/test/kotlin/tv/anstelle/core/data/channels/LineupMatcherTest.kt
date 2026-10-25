package tv.anstelle.core.data.channels

import tv.anstelle.core.data.episodes
import tv.anstelle.core.data.item
import tv.anstelle.core.data.seriesRow
import tv.anstelle.core.database.dao.CatalogRow
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LineupMatcherTest {
    private val catalog = Catalog(
        listOf(
            seriesRow(900, "s-office", "The Example Office"),
            item(1, title = "Pilot", series = "s-office", show = "The Example Office", season = 1, episode = 1, genres = listOf("Comedy"),
                 studios = listOf("Example Network"), year = 2005, rating = "TV-14", score = 14, minutes = 22),
            item(2, title = "Christmas Party", series = "s-office", show = "The Example Office", season = 2, episode = 10, genres = listOf("Comedy"),
                 studios = listOf("Example Network"), year = 2005, rating = "TV-14", score = 14, minutes = 22),
            item(3, type = MediaType.MOVIE, title = "A Christmas Example", genres = listOf("Family", "Comedy"),
                 studios = listOf("Example Pictures"), year = 1983, rating = "PG", score = 10, minutes = 94),
            item(4, type = MediaType.MOVIE, title = "The Party Planner", genres = listOf("Drama"), year = null, minutes = 120,
                 tags = listOf("trick-or-treat")),
            item(5, title = "Example Cadets", series = "s-space", genres = listOf("Animation", "Kids"), studios = listOf("Example Cartoon Studio"),
                 year = 1998, rating = "TV-Y7", score = 7, minutes = 11, library = "library-2"),
            item(6, type = MediaType.MUSIC_VIDEO, title = "Example Song", year = 1984, minutes = 4),
        ),
    )

    private fun ids(lineup: Lineup) = LineupMatcher(lineup).match(catalog).matches.map { it.item.id }

    private fun ids(vararg rules: Rule) = ids(Lineup(listOf(RuleGroup(rules.toList()))))

    @Test
    fun `series rows only name shows`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), catalog.items.map { it.id })
        assertEquals("The Example Office", catalog.titleOf("s-office"))
        assertEquals(listOf("A Christmas Example", "Example Song", "Show s-space", "The Example Office", "The Party Planner"), catalog.shows.map { it.title })
    }

    @Test
    fun `list fields ignore case`() {
        assertEquals(listOf(1L, 2L, 3L), ids(Rule(RuleField.GENRE, RuleOp.ANY_OF, "comedy")))
        assertEquals(listOf(4L, 5L, 6L), ids(Rule(RuleField.GENRE, RuleOp.NOT_ANY_OF, "Comedy")))
        assertEquals(listOf(1L, 2L), ids(Rule(RuleField.STUDIO, RuleOp.EQUALS, "example network")))
        assertEquals(listOf(5L), ids(Rule(RuleField.STUDIO, RuleOp.CONTAINS, "cartoon")))
    }

    @Test
    fun `rules in a group must all hold, and any group may match`() {
        val comedyEpisodes = RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Comedy"), Rule(RuleField.MEDIA_TYPE, RuleOp.EQUALS, "EPISODE")))
        assertEquals(listOf(1L, 2L), ids(Lineup(listOf(comedyEpisodes))))
        val kids = RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Kids")))
        assertEquals(listOf(1L, 2L, 5L), ids(Lineup(listOf(comedyEpisodes, kids))))
    }

    @Test
    fun `an item gets the highest weight of what includes it, and excludes win`() {
        val lineup = Lineup(
            groups = listOf(
                RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Comedy")), weight = 1f),
                RuleGroup(listOf(Rule(RuleField.MEDIA_TYPE, RuleOp.EQUALS, "MOVIE")), weight = 3f),
            ),
            includes = listOf(Pick(RuleField.SERIES, "s-space", weight = 2f), Pick(RuleField.ITEM, "item-1", weight = 5f)),
            excludes = listOf(Pick(RuleField.ITEM, "item-4"), Pick(RuleField.SERIES, "S-OFFICE")),
        )
        val weights = LineupMatcher(lineup).match(catalog).matches.associate { it.item.id to it.weight }
        // The office is excluded even though item 1 is included and matches a group.
        assertEquals(mapOf(3L to 3f, 5L to 2f), weights)
    }

    @Test
    fun `numbers compare, and missing values only pass NOT_ANY_OF`() {
        assertEquals(listOf(3L, 5L, 6L), ids(Rule(RuleField.YEAR, RuleOp.BETWEEN, 1980, 1999)))
        assertEquals(listOf(3L, 6L), ids(Rule(RuleField.DECADE, RuleOp.EQUALS, 1980)))
        assertEquals(listOf(1L, 2L, 3L, 5L), ids(Rule(RuleField.DECADE, RuleOp.ANY_OF, 1990, 2000, 1980), Rule(RuleField.MEDIA_TYPE, RuleOp.NOT_ANY_OF, "MUSIC_VIDEO")))
        assertEquals(listOf(4L), ids(Rule(RuleField.YEAR, RuleOp.NOT_ANY_OF, 2005, 1983, 1998, 1984)))
        assertEquals(listOf(1L, 2L), ids(Rule(RuleField.YEAR, RuleOp.AT_LEAST, 2000)))
        assertEquals(listOf(3L, 6L), ids(Rule(RuleField.YEAR, RuleOp.AT_MOST, 1990)))
    }

    @Test
    fun `ratings compare by text or by score, and unrated items fail limits`() {
        assertEquals(listOf(3L, 5L), ids(Rule(RuleField.OFFICIAL_RATING, RuleOp.AT_MOST, 10)))
        assertEquals(listOf(1L, 2L), ids(Rule(RuleField.OFFICIAL_RATING, RuleOp.AT_LEAST, 13)))
        assertEquals(listOf(5L), ids(Rule(RuleField.OFFICIAL_RATING, RuleOp.ANY_OF, "tv-y7", "TV-Y")))
        assertEquals(listOf(3L, 4L, 5L, 6L), ids(Rule(RuleField.OFFICIAL_RATING, RuleOp.NOT_ANY_OF, "TV-14")))
    }

    @Test
    fun `keywords match whole words and phrases in titles and tags`() {
        assertEquals(listOf(2L, 3L), ids(Rule(RuleField.KEYWORD, RuleOp.ANY_OF, "Christmas")))
        // "Party" does not contain the word "art"; the show title counts too.
        assertEquals(emptyList(), ids(Rule(RuleField.KEYWORD, RuleOp.ANY_OF, "art")))
        assertEquals(listOf(1L, 2L), ids(Rule(RuleField.KEYWORD, RuleOp.ANY_OF, "example office")))
        // Punctuation is ignored on both sides.
        assertEquals(listOf(4L), ids(Rule(RuleField.KEYWORD, RuleOp.ANY_OF, "trick or treat")))
        assertEquals(listOf(1L, 4L, 5L, 6L), ids(Rule(RuleField.KEYWORD, RuleOp.NOT_ANY_OF, "christmas", "x-mas")))
    }

    @Test
    fun `ids and types`() {
        assertEquals(listOf(3L, 4L), ids(Rule(RuleField.MEDIA_TYPE, RuleOp.EQUALS, "movie")))
        assertEquals(listOf(5L), ids(Rule(RuleField.SERIES, RuleOp.ANY_OF, "s-space")))
        assertEquals(listOf(3L), ids(Rule(RuleField.ITEM, RuleOp.EQUALS, "item-3")))
        assertEquals(listOf(5L), ids(Rule(RuleField.LIBRARY, RuleOp.EQUALS, "library-2")))
    }

    @Test
    fun `match results count items, shows and time`() {
        val result = LineupMatcher(Lineup(listOf(RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Comedy")))))).match(catalog)
        assertEquals(3, result.itemCount)
        assertEquals(2, result.showCount)
        assertEquals((22 + 22 + 94) * 60_000L, result.totalDurationMs)
    }

    @Test
    fun `invalid rules are reported`() {
        assertNull(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Comedy").problem())
        assertNotNull(Rule(RuleField.GENRE, RuleOp.AT_MOST, "3").problem())
        assertNotNull(Rule(RuleField.YEAR, RuleOp.BETWEEN, 1999, 1990).problem())
        assertNotNull(Rule(RuleField.YEAR, RuleOp.AT_LEAST, "soon").problem())
        assertNotNull(Rule(RuleField.DECADE, RuleOp.EQUALS, 1985).problem())
        assertNotNull(Rule(RuleField.MEDIA_TYPE, RuleOp.EQUALS, "SERIES").problem())
        assertNotNull(Rule(RuleField.YEAR, RuleOp.AT_MOST, 1990, 2000).problem())
        assertNotNull(Rule(RuleField.GENRE, RuleOp.ANY_OF, emptyList()).problem())
        assertNotNull(Rule(RuleField.SERIES, RuleOp.INCLUDE, "s").problem())
        assertNotNull(Lineup(listOf(RuleGroup(emptyList()))).problem())
        assertNotNull(Lineup(listOf(RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "x")), weight = 0f))).problem())
    }

    @Test
    fun `big libraries match quickly`() {
        val rows = ArrayList<CatalogRow>()
        repeat(500) { show -> rows += episodes("s$show", show * 100L, 100, genres = listOf("Comedy", "Genre ${show % 20}"), year = 1950 + show % 70) }
        val big = Catalog(rows)
        val lineup = Lineup(
            listOf(
                RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, "Genre 3", "Genre 7"), Rule(RuleField.DECADE, RuleOp.EQUALS, 1980))),
                RuleGroup(listOf(Rule(RuleField.KEYWORD, RuleOp.ANY_OF, "item 4242"))),
            ),
        )
        val matcher = LineupMatcher(lineup)
        matcher.match(big)
        // Best of ten, so a GC pause or a busy CI runner can't fail it.
        val bestMs = (1..10).minOf {
            val started = System.nanoTime()
            matcher.match(big)
            (System.nanoTime() - started) / 1_000_000
        }
        // 50,000 items: ~11 ms on a desktop, so the bound only trips on an algorithmic regression.
        kotlin.test.assertTrue(bestMs < 250, "matching 50k items took $bestMs ms")
    }
}
