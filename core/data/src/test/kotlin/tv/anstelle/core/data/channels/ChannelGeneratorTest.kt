package tv.anstelle.core.data.channels

import tv.anstelle.core.data.item
import tv.anstelle.core.database.dao.CatalogRow
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelGeneratorTest {
    private val shipped = File("src/main/assets/channel-words.json").readText()

    /** The shipped word list without its channel names, so channels are named from their genres. */
    private val words = ChannelWords.parse(JsonObject(Json.parseToJsonElement(shipped).jsonObject - "names" - "movieNames").toString())
    private val generator = ChannelGenerator(words)
    private var nextId = 1L

    /** A series of [episodes] half-hour episodes: 6 hours by default, so 3 shows make a channel. */
    private fun show(
        key: String,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        studios: List<String> = emptyList(),
        year: Int? = 2005,
        score: Int? = null,
        episodes: Int = 12,
        audio: List<String> = emptyList(),
    ): List<CatalogRow> = List(episodes) { e ->
        item(
            id = nextId++, series = key, season = 1, episode = e + 1, minutes = 30,
            genres = genres, tags = tags, studios = studios, year = year, score = score, audio = audio,
        )
    }

    private fun movie(
        genres: List<String> = emptyList(),
        studios: List<String> = emptyList(),
        year: Int? = 2005,
        tags: List<String> = emptyList(),
        audio: List<String> = emptyList(),
    ) = listOf(item(id = nextId++, type = MediaType.MOVIE, minutes = 120, genres = genres, studios = studios, year = year, tags = tags, audio = audio))

    private fun shows(
        count: Int,
        prefix: String,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        year: Int? = 2005,
        audio: List<String> = emptyList(),
    ) = (1..count).flatMap { show("$prefix-$it", genres = genres, tags = tags, year = year, audio = audio) }

    private fun catalog(rows: List<CatalogRow>) = Catalog(rows, words.types)

    private fun generate(rows: List<CatalogRow>) = generator.generate(catalog(rows))

    private fun names(rows: List<CatalogRow>) = generate(rows).map { it.name }

    @Test
    fun `genres pair up and name media types with one modifier each`() {
        val channels = generate(
            shows(3, "comedy", listOf("Comedy")) +
                shows(3, "drama", listOf("Drama")) +
                shows(3, "dramedy", listOf("Comedy", "Drama")) +
                shows(3, "crime-drama", listOf("Crime", "Drama")) +
                shows(2, "crime", listOf("Crime")) +
                shows(3, "romcom", listOf("Romance", "Comedy")) +
                shows(2, "romance", listOf("Romance")) +
                shows(3, "actcom", listOf("Action", "Comedy")) +
                shows(3, "actadv", listOf("Action", "Adventure")) +
                shows(2, "adventure", listOf("Adventure")) +
                shows(3, "anime-comedy", listOf("Anime", "Comedy")) +
                shows(3, "anime-sports", listOf("Anime", "Sport")) +
                shows(3, "tmdb-anime", listOf("Animation", "Action"), tags = listOf("anime")) +
                shows(3, "cartoon-comedy", listOf("Animation", "Comedy")) +
                shows(3, "cartoon-drama", listOf("Animation", "Drama")),
        )
        val names = channels.map { it.name }
        for (expected in listOf(
            "Comedy", "Drama", "Crime", "Romance", "Action", "Adventure",
            "Comedic Drama", "Crime Drama", "Romantic Comedy", "Action Comedy", "Action Adventure",
            "Comedic Anime", "Sports Anime", "Action Anime", "Comedic Cartoons", "Drama Cartoons",
        )) {
            assertTrue(expected in names, "$expected missing from $names")
        }
        // A media type never airs alone, and no name joins two genres with "&".
        assertTrue(names.none { it in setOf("Anime", "Cartoons", "Animation", "Cartoon", "Sports") }, names.toString())
        assertTrue(names.none { "&" in it })

        // Plain genres leave anime and cartoons out.
        val comedy = channels.single { it.name == "Comedy" }
        assertEquals(setOf("comedy", "dramedy", "romcom", "actcom"), comedy.match.matches.map { it.item.showKey.substringBefore('-') }.toSet())
        assertTrue(channels.all { it.segment == ChannelSegment.GENRES || it.segment == ChannelSegment.DECADES })
    }

    @Test
    fun `genre channels take their names from the word list, else from their genres`() {
        val named = ChannelWords.parse(shipped)
        val names = ChannelGenerator(named).generate(
            Catalog(
                shows(3, "anime-horror", listOf("Anime", "Horror")) +
                    shows(3, "anime-comedy", listOf("Anime", "Comedy")) +
                    shows(3, "crime-drama", listOf("Crime", "Drama")) +
                    shows(2, "crime", listOf("Crime")) +
                    shows(2, "drama", listOf("Drama")) +
                    shows(3, "odd-drama", listOf("Oddball", "Drama")) +
                    shows(2, "odd", listOf("Oddball")) +
                    (1..6).flatMap { movie(listOf("Horror")) },
                named.types,
            ),
        ).map { it.name }
        for (expected in listOf("Anime After Dark", "Anime Funhouse", "Case Files", "Crime Beat", "Center Stage", "Oddball", "Oddball Drama", "Horror Picture Show")) {
            assertTrue(expected in names, "$expected missing from $names")
        }
    }

    @Test
    fun `compound genres split and spellings merge`() {
        val channels = generate(shows(3, "sff", listOf("Sci-Fi & Fantasy")) + shows(2, "sf", listOf("Science Fiction")))
        val sciFi = channels.single { it.name == "Sci-Fi" }
        assertEquals(5, sciFi.match.showCount)
        assertTrue(channels.any { it.name == "Fantasy" })
        assertTrue(channels.none { "&" in it.name })
        val values = sciFi.lineup.groups.single().rules.first().values
        assertEquals(listOf("Sci-Fi & Fantasy", "Science Fiction"), values)
    }

    @Test
    fun `anime wins over animation`() {
        val names = names(shows(3, "a", listOf("Anime", "Animation", "Comedy")) + shows(3, "b", listOf("Anime", "Drama")))
        assertTrue("Comedic Anime" in names)
        assertFalse("Comedic Cartoons" in names)
    }

    @Test
    fun `tags need more shows and skip genres, media types, junk and the generic`() {
        val isekai = listOf("isekai", "based on manga", "comedy", "anime", "duringcreditsstinger")
        val channels = generate(
            shows(4, "isekai-f", listOf("Fantasy"), tags = isekai) +
                shows(4, "isekai-a", listOf("Action"), tags = isekai) +
                shows(9, "anime", listOf("Comedy"), tags = listOf("anime", "based on manga")) +
                shows(7, "girls", listOf("Drama"), tags = listOf("magical girl", "based on manga", "amused")) +
                shows(3, "other", listOf("Comedy")),
        )
        val themes = channels.filter { it.segment == ChannelSegment.THEMES }.map { it.name }
        // "based on manga" is on 24 of 27 shows; "magical girl" is on only 7; "amused" is a mood.
        assertEquals(listOf("Isekai"), themes)
    }

    @Test
    fun `a tag on just as many shows as a genre adds nothing`() {
        val themes = generate(shows(8, "x", listOf("Fantasy"), tags = listOf("wizards")) + shows(9, "y", listOf("Drama")))
            .filter { it.segment == ChannelSegment.THEMES }
        assertEquals(emptyList(), themes)
    }

    @Test
    fun `decades split TV, movies and media types`() {
        val names = names(
            shows(3, "ninety", listOf("Drama"), year = 1995) +
                shows(3, "noughties", listOf("Drama"), year = 2003) +
                (1..6).flatMap { movie(listOf("Drama"), year = 1995) } +
                show("anime-1", listOf("Anime", "Drama"), year = 2012) +
                show("anime-2", listOf("Anime", "Comedy"), year = 2012) +
                show("anime-3", listOf("Anime", "Horror"), year = 2012) +
                (1..6).flatMap { movie(listOf("Drama"), year = 1985) },
        )
        assertTrue("90s TV" in names, names.toString())
        assertTrue("2010s Anime" in names, names.toString())
        assertTrue("80s Movies" in names, names.toString())
        assertFalse("2010s TV" in names)
        assertEquals("2000s", ChannelGenerator.decadeLabel(2000))
        assertEquals("1910s", ChannelGenerator.decadeLabel(1910))
        assertEquals("20s", ChannelGenerator.decadeLabel(1920))
    }

    @Test
    fun `a studio's channel airs its series and movies, and one studio spelled twice is one channel`() {
        val channels = generate(
            (1..3).flatMap { show("n$it", studios = listOf("Netflix"), year = null) } +
                (1..6).flatMap { movie(studios = listOf("Netflix"), year = null) } +
                (1..3).flatMap { show("t$it", studios = listOf("Toho", "Toho Co., Ltd."), year = null) },
        ).filter { it.segment == ChannelSegment.PRODUCTIONS }
        val netflix = channels.single { it.name == "Netflix" }
        assertEquals(9, netflix.match.showCount)
        assertEquals(PlaybackOrder.EPISODE_ORDER, netflix.order)
        assertEquals(1, channels.count { it.name.startsWith("Toho") })
    }

    @Test
    fun `a studio inside a bigger one adds nothing`() {
        val names = generate(
            (1..2).flatMap { show("b$it", studios = listOf("Big Network"), year = null) } +
                (1..3).flatMap { show("r$it", studios = listOf("Big Network", "Regional TV"), year = null) },
        ).filter { it.segment == ChannelSegment.PRODUCTIONS }.map { it.name }
        assertEquals(listOf("Big Network"), names)
    }

    @Test
    fun `lower thresholds make smaller channels`() {
        val rows = shows(2, "w", listOf("Western")) + shows(3, "d", listOf("Drama"))
        assertFalse("Western" in names(rows))
        assertTrue("Western" in ChannelGenerator(words, scale = 0.5).generate(catalog(rows)).map { it.name })
    }

    @Test
    fun `themes and productions mix series, movies, anime and cartoons`() {
        val channels = generate(
            (1..3).flatMap { show("a$it", listOf("Anime"), tags = listOf("robots"), studios = listOf("Example Studio"), audio = listOf("jpn"), year = null) } +
                (1..3).flatMap { show("c$it", listOf("Animation"), tags = listOf("robots"), studios = listOf("Example Studio"), audio = listOf("eng"), year = null) } +
                (1..3).flatMap { movie(listOf("Drama"), tags = listOf("robots"), studios = listOf("Example Studio"), year = null) } +
                (1..3).flatMap { show("studio$it", listOf("Western"), studios = listOf("Example Studio"), year = null) } +
                (1..7).flatMap { show("other$it", listOf("Western"), year = null) },
        )
        for (name in listOf("Robots", "Example Studio")) {
            val items = channels.single { it.name == name }.match.matches.map { it.item }
            assertEquals(setOf("anime", "cartoons", MediaKinds.NONE), items.mapTo(HashSet()) { it.kind }, name)
            assertEquals(setOf(MediaType.EPISODE, MediaType.MOVIE), items.mapTo(HashSet()) { it.row.type }, name)
        }
    }

    @Test
    fun `movies only shuffle`() {
        val channel = generate((1..6).flatMap { movie(listOf("Western")) }).single { it.name == "Western Movies" }
        assertEquals(PlaybackOrder.SHUFFLE, channel.order)
    }

    @Test
    fun `segments in order, names alphabetical, decades by year`() {
        val channels = generate(
            shows(4, "c", listOf("Comedy"), tags = listOf("zombies"), year = 2003) +
                shows(4, "a", listOf("Action"), tags = listOf("zombies"), year = 1994) +
                (1..5).flatMap { show("w$it", genres = listOf("Western"), studios = listOf("Example Studio"), year = 1994) } +
                (1..4).flatMap { show("s$it", genres = listOf("Mystery"), studios = listOf("Example Studio"), year = 2003) },
        )
        val segments = channels.map { it.segment.ordinal }
        assertEquals(segments.sorted(), segments)
        val genres = channels.filter { it.segment == ChannelSegment.GENRES }.map { it.name }
        assertEquals(genres.sortedBy { it.lowercase() }, genres)
        assertEquals(listOf("90s TV", "2000s TV"), channels.filter { it.segment == ChannelSegment.DECADES }.map { it.name })
        assertTrue(channels.any { it.segment == ChannelSegment.THEMES && it.name == "Zombies" })
        assertTrue(channels.any { it.segment == ChannelSegment.PRODUCTIONS })
    }

    @Test
    fun `a rating limit counts only what the profile may watch`() {
        val rows = (1..3).flatMap { show("k$it", listOf("Family"), score = 7) } + (1..3).flatMap { show("u$it", listOf("Family"), score = null) }
        val all = generator.generate(catalog(rows)).single { it.name == "Family" }
        val kids = generator.generate(catalog(rows), maxRatingScore = 10).single { it.name == "Family" }
        assertEquals(6, all.match.showCount)
        assertEquals(3, kids.match.showCount)
        assertEquals(emptyList(), generator.generate(catalog(rows), maxRatingScore = 5))
    }

    @Test
    fun `series and movies never share a channel`() {
        val channels = generate(shows(3, "c", listOf("Comedy")) + (1..6).flatMap { movie(listOf("Comedy")) })
        assertEquals(setOf(MediaType.EPISODE), channels.single { it.name == "Comedy" }.match.matches.mapTo(HashSet()) { it.item.row.type })
        assertEquals(setOf(MediaType.MOVIE), channels.single { it.name == "Comedy Movies" }.match.matches.mapTo(HashSet()) { it.item.row.type })
    }

    @Test
    fun `a show is anime only when its tags and its audio agree`() {
        val rows = show("tagged-jp", listOf("Anime"), audio = listOf("eng", "jpn")) +
            show("tmdb", listOf("Animation"), tags = listOf("anime"), audio = listOf("jpn")) +
            show("unknown-audio", listOf("Anime")) +
            show("dub-only", listOf("Anime"), audio = listOf("eng")) +
            show("western", listOf("Animation"), audio = listOf("eng", "jpn")) +
            show("untagged-jp", listOf("Animation"), audio = listOf("jpn", "eng")) +
            show("donghua", listOf("Animation"), audio = listOf("zho")) +
            show("live", listOf("Drama"), audio = listOf("jpn"))
        val kinds = catalog(rows).items.associate { it.showKey to it.kind }
        assertEquals("anime", kinds["tagged-jp"])
        assertEquals("anime", kinds["tmdb"])
        assertEquals("anime", kinds["unknown-audio"])
        assertEquals(MediaKinds.UNCLEAR, kinds["dub-only"])
        // A Japanese dub doesn't matter while the show plays another language first.
        assertEquals("cartoons", kinds["western"])
        assertEquals(MediaKinds.UNCLEAR, kinds["untagged-jp"])
        assertEquals("cartoons", kinds["donghua"])
        assertEquals(MediaKinds.NONE, kinds["live"])
    }

    @Test
    fun `one episode playing Japanese first keeps a whole untagged show out of cartoons`() {
        val rows = List(11) { item(id = nextId++, series = "s", genres = listOf("Animation"), audio = listOf("eng")) } +
            item(id = nextId++, series = "s", genres = listOf("Animation"), audio = listOf("jpn"))
        assertEquals(setOf(MediaKinds.UNCLEAR), catalog(rows).items.mapTo(HashSet()) { it.kind })
    }

    @Test
    fun `unclear animation airs on no anime, cartoon or plain channel`() {
        val channels = generate(
            shows(3, "anime", listOf("Anime", "Comedy"), audio = listOf("jpn")) +
                shows(3, "anime-drama", listOf("Anime", "Drama"), audio = listOf("jpn")) +
                shows(3, "toon", listOf("Animation", "Comedy"), audio = listOf("eng")) +
                shows(3, "toon-drama", listOf("Animation", "Drama"), audio = listOf("eng")) +
                shows(3, "live", listOf("Comedy")) +
                shows(3, "unclear", listOf("Animation", "Comedy"), audio = listOf("jpn")),
        )
        val unclear = channels.filter { channel -> channel.match.matches.any { it.item.showKey.startsWith("unclear") } }
        assertEquals(emptyList(), unclear.map { it.name })
        assertTrue(channels.any { it.name == "Comedic Anime" } && channels.any { it.name == "Comedic Cartoons" } && channels.any { it.name == "Comedy" })
    }

    /** A library with a bit of everything, in a random order. */
    private fun mixedLibrary(random: Random): List<CatalogRow> {
        val genres = listOf("Comedy", "Drama", "Crime", "Romance", "Sci-Fi & Fantasy", "Science Fiction", "Anime", "Animation", "Sport", "Horror")
        val tags = listOf("isekai", "anime", "zombies", "time travel", "based on manga", "magical girl")
        val studios = listOf("Netflix", "NBC", "TBS", "Example Studio")
        val audio = listOf(emptyList(), listOf("jpn"), listOf("eng"), listOf("eng", "jpn"), listOf("jpn", "eng"))
        return (1..160).flatMap { s ->
            val showGenres = genres.shuffled(random).take(random.nextInt(1, 4))
            val showTags = tags.shuffled(random).take(random.nextInt(0, 3))
            val showAudio = audio.random(random)
            if (random.nextInt(3) == 0) {
                movie(showGenres, studios.shuffled(random).take(1), year = 1970 + random.nextInt(55), tags = showTags, audio = showAudio)
            } else {
                show("s$s", showGenres, showTags, studios.shuffled(random).take(random.nextInt(0, 2)), year = 1970 + random.nextInt(55), episodes = random.nextInt(4, 14), audio = showAudio)
            }
        }
    }

    @Test
    fun `every lineup matches exactly the items the channel counted, and genres and decades keep to one kind and one medium`() {
        val rows = mixedLibrary(Random(7))
        val catalog = catalog(rows)
        val channels = generator.generate(catalog)
        assertTrue(channels.size > 20, "only ${channels.size} channels")
        assertTrue(catalog.items.any { it.kind == MediaKinds.UNCLEAR })
        for (channel in channels) {
            assertEquals(null, channel.lineup.problem(), channel.key)
            val matched = LineupMatcher(channel.lineup).match(catalog).matches
            assertEquals(channel.match.matches.map { it.item.id }, matched.map { it.item.id }, channel.key)
            // Themes and productions may mix anything.
            if (channel.segment == ChannelSegment.THEMES || channel.segment == ChannelSegment.PRODUCTIONS) continue
            assertEquals(1, matched.mapTo(HashSet()) { it.item.kind }.size, "${channel.name} mixes kinds")
            assertTrue(matched.none { it.item.kind == MediaKinds.UNCLEAR }, "${channel.name} airs unclear animation")
            assertEquals(1, matched.mapTo(HashSet()) { it.item.row.type }.size, "${channel.name} mixes series and movies")
        }
    }

    @Test
    fun `the same library in any order gives the same channels`() {
        val rows = mixedLibrary(Random(11))
        val first = generator.generate(catalog(rows))
        val second = generator.generate(catalog(rows.shuffled(Random(3))))
        assertEquals(first.map { it.key to it.name }, second.map { it.key to it.name })
        assertEquals(first.map { it.lineup }, second.map { it.lineup })
    }

    @Test
    fun `50,000 items generate quickly`() {
        val random = Random(1)
        val genres = List(24) { "Genre $it" } + listOf("Anime", "Animation")
        val tags = List(400) { "tag $it" }
        val studios = List(150) { "Studio $it" }
        val rows = (1..2_500).flatMap { s ->
            show(
                "s$s", genres.shuffled(random).take(3), tags.shuffled(random).take(4), studios.shuffled(random).take(2),
                year = 1960 + random.nextInt(65), episodes = 20,
            )
        }
        val catalog = catalog(rows)
        assertEquals(50_000, catalog.items.size)
        generator.generate(catalog)
        val start = System.nanoTime()
        val channels = generator.generate(catalog)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(channels.isNotEmpty())
        assertTrue(ms < 1_000, "took $ms ms")
    }
}
