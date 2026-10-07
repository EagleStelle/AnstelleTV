package tv.anstelle.core.data.channels

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelWordsTest {
    /** The word list that ships in the APK, read from the module's assets folder. */
    private val words = ChannelWords.parse(File("src/main/assets/channel-words.json").readText())

    @Test
    fun `the shipped word list parses`() {
        assertEquals(listOf("Anime", "Cartoons"), words.types.map { it.name })
        assertEquals(listOf("jpn", null), words.types.map { it.language })
        assertTrue("animation" in words.types[1].genreKeys)
        assertTrue("anime" in words.types[0].tagKeys)
    }

    @Test
    fun `spellings of one genre share a key and a name`() {
        val key = words.genreKey("Science Fiction")
        assertEquals(key, words.genreKey("Sci-Fi"))
        assertEquals(key, words.genreKey("science-fiction"))
        assertEquals("Sci-Fi", words.genreName(key))
        assertEquals(words.genreKey("Sport"), words.genreKey("Sports"))
        assertNull(words.genreName(words.genreKey("Drama")))
    }

    @Test
    fun `modifiers, title case and ignored tags`() {
        assertEquals("Comedic", words.modifier(words.genreKey("Comedy")))
        assertEquals("Romantic", words.modifier(words.genreKey("romance")))
        assertNull(words.modifier(words.genreKey("Drama")))
        assertTrue(words.isHead(words.genreKey("Drama")))
        assertFalse(words.isHead(words.genreKey("Action")))
        assertEquals("Based on Manga", words.titleCase("based on manga"))
        assertEquals("Magical Girl", words.titleCase("magical girl"))
        assertEquals("MythBusters Style", words.titleCase("MythBusters Style"))
        assertTrue(words.isIgnoredTag(valueKey("duringcreditsstinger")))
    }

    @Test
    fun `every named genre has names alone, with each media type and with each other genre`() {
        val genres = words.namedGenres.sorted()
        assertTrue(genres.size >= 30, "only ${genres.size} named genres")
        val names = ArrayList<String>()
        for (movies in listOf(false, true)) {
            for ((i, genre) in genres.withIndex()) {
                names += assertNotNull(words.nameOf(genre, movies), "$genre, movies $movies")
                for (kind in words.types) names += assertNotNull(words.nameOf(kind, genre, movies), "${kind.name} + $genre, movies $movies")
                for (other in genres.drop(i + 1)) names += assertNotNull(words.nameOf(genre, other, movies), "$genre + $other, movies $movies")
            }
        }
        assertEquals(1400, names.size)
        assertEquals(names.size, names.toSet().size, "names repeat: ${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}")
        assertTrue(names.all { it.split(' ').size <= 3 && '&' !in it }, "a name is too long or joins with &")
        assertEquals("Anime After Dark", words.nameOf(words.types.single { it.name == "Anime" }, words.genreKey("Horror")))
        assertEquals("Case Files", words.nameOf(words.genreKey("Drama"), words.genreKey("Crime")))
        assertEquals(words.nameOf(words.genreKey("Sci-Fi")), words.nameOf(words.genreKey("Science Fiction")))
    }

    @Test
    fun `accents and punctuation don't change a key`() {
        assertEquals("shonen", valueKey("Shōnen"))
        assertEquals("sci fi", valueKey("Sci-Fi"))
    }

    @Test
    fun `a broken word list says what is wrong`() {
        assertFailsWith<IllegalArgumentException> { ChannelWords.parse("{nope") }
        val noWords = assertFailsWith<IllegalArgumentException> { ChannelWords.parse("""{"types": [{"name": "Anime"}]}""") }
        assertTrue("Anime" in noWords.message.orEmpty())
        val repeated = assertFailsWith<IllegalArgumentException> {
            ChannelWords.parse("""{"types": [{"name": "Anime", "genres": ["Anime"]}, {"name": "anime", "tags": ["anime"]}]}""")
        }
        assertTrue("repeat" in repeated.message.orEmpty())
    }
}
