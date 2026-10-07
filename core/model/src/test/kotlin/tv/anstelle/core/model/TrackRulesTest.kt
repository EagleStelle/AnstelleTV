package tv.anstelle.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackRulesTest {
    private fun sub(language: String?, forced: Boolean = false, default: Boolean = false) = SubtitleCandidate(language, forced, default)

    private fun prefs(mode: SubtitleMode, vararg languages: String) = TrackPrefs(subtitleMode = mode, subtitleLanguages = languages.toList())

    private val anime = listOf(sub("en", forced = true), sub("eng"), sub("spa", default = true), sub("por"))

    @Test
    fun `off shows nothing`() {
        assertNull(TrackRules.pickSubtitle(anime, prefs(SubtitleMode.OFF, "eng"), "jpn"))
    }

    @Test
    fun `always follows the language order`() {
        assertEquals(1, TrackRules.pickSubtitle(anime, prefs(SubtitleMode.ALWAYS, "eng", "spa"), "eng"))
        assertEquals(2, TrackRules.pickSubtitle(anime, prefs(SubtitleMode.ALWAYS, "fra", "spa", "eng"), "jpn"))
    }

    @Test
    fun `no listed language shows nothing, even with a default track`() {
        assertNull(TrackRules.pickSubtitle(anime, prefs(SubtitleMode.ALWAYS, "fra", "deu"), "jpn"))
        assertNull(TrackRules.pickSubtitle(anime, prefs(SubtitleMode.ALWAYS), "jpn"))
    }

    @Test
    fun `full track beats forced, then default wins`() {
        val tracks = listOf(sub("eng", forced = true, default = true), sub("eng"), sub("eng", default = true))
        assertEquals(2, TrackRules.pickSubtitle(tracks, prefs(SubtitleMode.ALWAYS, "eng"), "jpn"))
        assertEquals(0, TrackRules.pickSubtitle(listOf(sub("eng", forced = true)), prefs(SubtitleMode.ALWAYS, "eng"), "jpn"))
    }

    @Test
    fun `when audio differs`() {
        val mode = prefs(SubtitleMode.WHEN_AUDIO_DIFFERS, "eng")
        assertEquals(1, TrackRules.pickSubtitle(anime, mode, "jpn"))
        assertEquals(1, TrackRules.pickSubtitle(anime, mode, "ja"))
        assertNull(TrackRules.pickSubtitle(anime, mode, "en"))
        assertNull(TrackRules.pickSubtitle(anime, mode, "eng"))
        // Any subtitle language counts as understood.
        assertNull(TrackRules.pickSubtitle(anime, prefs(SubtitleMode.WHEN_AUDIO_DIFFERS, "eng", "spa"), "spa"))
        // Unknown audio is treated as another language.
        assertEquals(1, TrackRules.pickSubtitle(anime, mode, null))
        assertEquals(1, TrackRules.pickSubtitle(anime, mode, "und"))
    }

    @Test
    fun `audio follows the language order`() {
        val streams = listOf(AudioStream(1, "eng", isDefault = true), AudioStream(2, "kor"), AudioStream(3, "jpn"), AudioStream(4, "jpn", isDefault = true))
        assertEquals(4, TrackRules.pickAudio(streams, listOf("jpn", "kor", "eng"))?.index)
        assertEquals(2, TrackRules.pickAudio(streams.filter { it.language != "jpn" }, listOf("jpn", "kor", "eng"))?.index)
        assertEquals(3, TrackRules.pickAudio(listOf(AudioStream(3, "ja"), AudioStream(1, "eng")), listOf("jpn"))?.index)
    }

    @Test
    fun `audio without a listed language is left to the server`() {
        assertNull(TrackRules.pickAudio(listOf(AudioStream(1, "eng", isDefault = true)), listOf("jpn")))
        assertNull(TrackRules.pickAudio(listOf(AudioStream(1, "eng")), emptyList()))
    }

    @Test
    fun `tracks round-trip through storage`() {
        val tracks = MediaTracks(
            audio = listOf(AudioStream(1, "jpn", isDefault = true)),
            subtitles = listOf(SubtitleStream(3, "eng", "Full", "ass", isDefault = true), SubtitleStream(9, "eng", null, "subrip", isExternal = true)),
            fonts = listOf(FontAttachment(0, "Example.ttf")),
        )
        assertEquals(tracks, MediaTracks.decode(tracks.encode()))
        assertEquals("", MediaTracks.EMPTY.encode())
        assertEquals(MediaTracks.EMPTY, MediaTracks.decode(""))
        assertEquals(true, tracks.hasAss)
        assertEquals(false, SubtitleStream(4, codec = "PGSSUB").isText)
    }
}
