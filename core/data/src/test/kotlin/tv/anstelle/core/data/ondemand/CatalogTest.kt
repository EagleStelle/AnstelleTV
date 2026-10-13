package tv.anstelle.core.data.ondemand

import tv.anstelle.core.data.Artwork
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.Playback
import tv.anstelle.core.model.PlaybackOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private fun channel(id: Long, number: Int) = Channel(
        id = id,
        number = number,
        name = "Channel $number",
        logoUri = null,
        kind = ChannelKind.GENERATED,
        series = Playback(PlaybackOrder.EPISODE_ORDER),
        movies = Playback(PlaybackOrder.SHUFFLE),
        moviesOncePerDay = false,
        isHidden = false,
        isFavorite = false,
        groupId = 1,
        fromLibrary = true,
    )

    private val defaults = listOf(Shelf(ShelfType.CONTINUE), Shelf(ShelfType.NEXT_UP), Shelf(ShelfType.RECENT)) +
        listOf(channel(11, 1001), channel(12, 1002), channel(13, 1003)).map { Shelf(ShelfType.CHANNEL, it) }

    @Test
    fun `rows keep their default order until one is moved`() {
        assertEquals(defaults, ShelfOrder.arrange(defaults, ShelfOrder.decode("")))
    }

    @Test
    fun `a channel moved first leads, and the rest keep their places`() {
        val stored = ShelfOrder.moved(defaults, from = 4, to = 0)
        val arranged = ShelfOrder.arrange(defaults, stored)
        assertEquals(listOf("channel:12", "continue", "nextup", "recent", "channel:11", "channel:13"), arranged.map { it.key })
        assertEquals(stored, ShelfOrder.decode(ShelfOrder.encode(stored)))
    }

    @Test
    fun `a new channel follows the stored order and a gone one drops out`() {
        val stored = listOf("channel:13", "recent", "continue", "channel:99", "nextup", "channel:11", "channel:12")
        val newer = defaults + Shelf(ShelfType.CHANNEL, channel(14, 1004))
        assertEquals(
            listOf("channel:13", "recent", "continue", "nextup", "channel:11", "channel:12", "channel:14"),
            ShelfOrder.arrange(newer, stored).map { it.key },
        )
    }

    private fun episode(id: Long, season: Int?, number: Int) =
        EpisodeEntry(id, "r$id", season, "S${season}E$number - Episode $number", "22 min", null, null, durationMs = 1_000_000)

    private val show = listOf(episode(1, 1, 1), episode(2, 1, 2), episode(3, 2, 1), episode(4, 2, 2), episode(9, 0, 1))

    @Test
    fun `a series starts at its first regular episode`() {
        assertEquals(1, NextEpisode.of(show, WatchMarks())?.id)
    }

    @Test
    fun `a series goes on after the last episode watched through`() {
        assertEquals(3, NextEpisode.of(show, WatchMarks(played = setOf(1, 2)))?.id)
        // Left near its end counts as watched through.
        assertEquals(2, NextEpisode.of(show, WatchMarks(resume = mapOf(1L to 990_000L)))?.id)
    }

    @Test
    fun `an episode left part-way after the last one watched is resumed`() {
        assertEquals(4, NextEpisode.of(show, WatchMarks(resume = mapOf(4L to 300_000L), played = setOf(1)))?.id)
    }

    @Test
    fun `a series watched to its last regular episode goes back to one not watched, or the first`() {
        assertEquals(2, NextEpisode.of(show, WatchMarks(played = setOf(1, 4)))?.id)
        assertEquals(1, NextEpisode.of(show, WatchMarks(played = setOf(1, 2, 3, 4)))?.id)
    }

    @Test
    fun `specials only lead a show that has nothing else`() {
        assertEquals(9, NextEpisode.of(listOf(episode(9, 0, 1)), WatchMarks())?.id)
        assertNull(NextEpisode.of(emptyList(), WatchMarks()))
    }

    private fun title(id: Long, name: String, kind: CatalogKind = CatalogKind.MOVIES, year: Int? = null, added: Long = 0, genres: List<String> = emptyList()) = Title(
        id = id,
        serverId = 1,
        remoteId = "r$id",
        kind = kind,
        title = name,
        sortKey = CatalogSearch.normalize(name),
        year = year,
        rating = null,
        genres = genres,
        overview = null,
        durationMs = if (kind == CatalogKind.MOVIES) 6_000_000 else 0,
        posterUrl = null,
        artwork = Artwork.None,
        addedUtcMs = added,
    )

    private val library = listOf(
        title(1, "The Long Night", year = 1999, added = 30, genres = listOf("Drama")),
        title(2, "Night Shift", CatalogKind.SERIES, year = 2015, added = 10, genres = listOf("Comedy")),
        title(3, "Nightfall", year = 2021, added = 20, genres = listOf("Horror", "Drama")),
        title(4, "Amélie's Night", year = null, genres = listOf("Comedy")),
        title(5, "Daybreak", year = 2005),
    )

    @Test
    fun `every word must appear in the title, accents and case aside`() {
        val found = CatalogSearch.search(library, "  NIGHT ", SearchFilters(), CatalogWatch()).titles.map { it.id }
        assertEquals(setOf(1L, 2L, 3L, 4L), found.toSet())
        assertEquals(listOf(4L), CatalogSearch.search(library, "amelie night", SearchFilters(), CatalogWatch()).titles.map { it.id })
        assertTrue(CatalogSearch.search(library, "   ", SearchFilters(), CatalogWatch()).titles.isEmpty())
    }

    @Test
    fun `best match puts titles starting with the search first`() {
        val found = CatalogSearch.search(library, "night", SearchFilters(), CatalogWatch()).titles.map { it.id }
        // "Night Shift" and "Nightfall" start with it; "The Long Night" and "Amélie's Night" only have a word starting with it.
        assertEquals(listOf(2L, 3L, 4L, 1L), found)
    }

    @Test
    fun `year sorts newest first when picked and turns round, undated titles last either way`() {
        val byYear = SearchFilters().sortedBy(SearchSort.YEAR)
        assertTrue(byYear.descending)
        assertEquals(listOf(3L, 2L, 1L, 4L), CatalogSearch.search(library, "night", byYear, CatalogWatch()).titles.map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), CatalogSearch.search(library, "night", byYear.reversed(), CatalogWatch()).titles.map { it.id })
    }

    @Test
    fun `title sorts A to Z when picked and Z to A turned round`() {
        val byTitle = SearchFilters().sortedBy(SearchSort.TITLE)
        assertFalse(byTitle.descending)
        assertEquals(listOf(4L, 2L, 3L, 1L), CatalogSearch.search(library, "night", byTitle, CatalogWatch()).titles.map { it.id })
        assertEquals(listOf(1L, 3L, 2L, 4L), CatalogSearch.search(library, "night", byTitle.reversed(), CatalogWatch()).titles.map { it.id })
        assertNull(SearchSort.BEST.orderLabel(true))
        assertEquals("Z to A", SearchSort.TITLE.orderLabel(true))
    }

    @Test
    fun `filters narrow what the words found, and the choices come from it`() {
        val results = CatalogSearch.search(library, "night", SearchFilters(genre = "drama", kind = CatalogKind.MOVIES), CatalogWatch())
        assertEquals(setOf(1L, 3L), results.titles.map { it.id }.toSet())
        assertEquals(4, results.found)
        assertEquals(listOf("Comedy", "Drama", "Horror"), results.genres)
        assertEquals(listOf(2020, 2010, 1990), results.decades)
        val nineties = CatalogSearch.search(library, "night", SearchFilters(decade = 1990), CatalogWatch())
        assertEquals(listOf(1L), nineties.titles.map { it.id })
    }

    @Test
    fun `watched filter reads the profile's marks`() {
        val watch = CatalogWatch(mapOf(1L to TitleProgress(0, played = true, unwatched = null), 3L to TitleProgress(5_900_000, played = false, unwatched = null)), known = true)
        val watched = CatalogSearch.search(library, "night", SearchFilters(watch = WatchFilter.WATCHED), watch).titles.map { it.id }
        // A movie left this close to its end counts as watched through.
        assertEquals(setOf(1L, 3L), watched.toSet())
        val unwatched = CatalogSearch.search(library, "night", SearchFilters(watch = WatchFilter.UNWATCHED), watch).titles.map { it.id }
        assertEquals(setOf(2L, 4L), unwatched.toSet())
    }

    @Test
    fun `a series counts its episodes left only when the server told`() {
        val series = title(2, "Night Shift", CatalogKind.SERIES)
        assertNull(CatalogWatch(mapOf(2L to TitleProgress(0, false, 7))).unwatched(series))
        assertEquals(7, CatalogWatch(mapOf(2L to TitleProgress(0, false, 7)), known = true).unwatched(series))
    }
}
