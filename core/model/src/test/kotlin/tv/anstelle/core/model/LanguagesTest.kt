package tv.anstelle.core.model

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagesTest {
    @Test
    fun `normalizes every spelling to ISO 639-2 T`() {
        assertEquals("jpn", Languages.normalize("ja"))
        assertEquals("jpn", Languages.normalize("jpn"))
        assertEquals("jpn", Languages.normalize("ja-JP"))
        assertEquals("eng", Languages.normalize(" EN "))
        assertEquals("kor", Languages.normalize("ko_KR"))
        assertEquals("fra", Languages.normalize("fre"))
        assertEquals("deu", Languages.normalize("ger"))
        assertEquals("zho", Languages.normalize("chi"))
        assertEquals("zho", Languages.normalize("zh"))
        assertEquals("heb", Languages.normalize("iw"))
    }

    @Test
    fun `undetermined and blank codes are unknown`() {
        assertNull(Languages.normalize(null))
        assertNull(Languages.normalize(""))
        assertNull(Languages.normalize("und"))
        assertNull(Languages.normalize("zxx"))
    }

    @Test
    fun `display names`() {
        assertEquals("Japanese", Languages.displayName("jpn", Locale.ENGLISH))
        assertEquals("French", Languages.displayName("fre", Locale.ENGLISH))
        assertEquals("xyz", Languages.displayName("xyz", Locale.ENGLISH))
    }

    @Test
    fun `all languages are unique and sorted`() {
        val all = Languages.all(Locale.ENGLISH)
        assertTrue(all.size > 150)
        assertEquals(all.size, all.map { it.code }.toSet().size)
        assertEquals(all.map { it.name.lowercase() }.sorted(), all.map { it.name.lowercase() })
        assertTrue(Language("kor", "Korean") in all)
    }

    @Test
    fun `language lists round-trip`() {
        assertEquals("jpn,kor,eng", Languages.encodeList(listOf("ja", "kor", "en", "jpn")))
        assertEquals(listOf("jpn", "kor", "eng"), Languages.decodeList("jpn,kor,eng"))
        assertEquals(listOf("eng"), Languages.decodeList("en"))
        assertEquals(emptyList(), Languages.decodeList(""))
    }
}
