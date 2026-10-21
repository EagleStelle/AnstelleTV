package tv.anstelle.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackRulesTest {
    private fun sub(language: String?, forced: Boolean = false, default: Boolean = false, title: String? = null) = SubtitleCandidate(language, title, forced, default)

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
    fun `always shows only full tracks, the default first`() {
        val tracks = listOf(sub("eng", forced = true, default = true), sub("eng"), sub("eng", default = true))
        assertEquals(2, TrackRules.pickSubtitle(tracks, prefs(SubtitleMode.ALWAYS, "eng"), "jpn"))
        assertNull(TrackRules.pickSubtitle(listOf(sub("eng", forced = true)), prefs(SubtitleMode.ALWAYS, "eng"), "jpn"))
    }

    @Test
    fun `a track titled forced counts as forced without the flag`() {
        val tracks = listOf(sub("eng", default = true, title = "English [Forced]"), sub("eng", default = true, title = "Full"), sub("spa", title = "Foreign parts"))
        assertEquals(1, TrackRules.pickSubtitle(tracks, prefs(SubtitleMode.ALWAYS, "eng"), "jpn"))
        assertEquals(0, TrackRules.pickSubtitle(tracks, prefs(SubtitleMode.FORCED, "eng"), "jpn"))
        assertEquals(2, TrackRules.pickSubtitle(tracks, prefs(SubtitleMode.SMART, "spa"), "spa"))
        assertEquals(0, TrackRules.pickSubtitle(listOf(sub("eng", title = "Unforced")), prefs(SubtitleMode.ALWAYS, "eng"), "jpn"))
    }

    @Test
    fun `forced shows only forced tracks, in the language order`() {
        val tracks = listOf(sub("eng"), sub("spa", forced = true), sub("eng", forced = true), sub("eng", forced = true, default = true))
        assertEquals(3, TrackRules.pickSubtitle(tracks, prefs(SubtitleMode.FORCED, "eng", "spa"), "eng"))
        assertEquals(1, TrackRules.pickSubtitle(tracks, prefs(SubtitleMode.FORCED, "fra", "spa", "eng"), "jpn"))
        assertNull(TrackRules.pickSubtitle(listOf(sub("eng")), prefs(SubtitleMode.FORCED, "eng"), "eng"))
    }

    @Test
    fun `smart shows full tracks for foreign audio`() {
        val mode = prefs(SubtitleMode.SMART, "eng")
        assertEquals(1, TrackRules.pickSubtitle(anime, mode, "jpn"))
        assertEquals(1, TrackRules.pickSubtitle(anime, mode, "ja"))
        // Unknown audio is treated as another language.
        assertEquals(1, TrackRules.pickSubtitle(anime, mode, null))
        assertEquals(1, TrackRules.pickSubtitle(anime, mode, "und"))
    }

    @Test
    fun `smart shows forced tracks in the audio's language when it is understood`() {
        assertEquals(0, TrackRules.pickSubtitle(anime, prefs(SubtitleMode.SMART, "eng"), "en"))
        // Any subtitle language counts as understood, and only its own forced track will do.
        assertNull(TrackRules.pickSubtitle(anime, prefs(SubtitleMode.SMART, "eng", "spa"), "spa"))
        assertNull(TrackRules.pickSubtitle(listOf(sub("eng")), prefs(SubtitleMode.SMART, "eng"), "eng"))
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
