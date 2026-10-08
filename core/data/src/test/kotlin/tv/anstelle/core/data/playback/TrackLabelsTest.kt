package tv.anstelle.core.data.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackLabelsTest {
    @Test
    fun `a language of its own is named by the language alone`() {
        val labels = TrackLabels.of(
            listOf(
                TrackFacts("Japanese", name = "Stereo", channels = "stereo", codec = "DD"),
                TrackFacts("English", name = "Dub", channels = "5.1"),
            ),
        )
        assertEquals(listOf("Japanese", "English"), labels)
    }

    @Test
    fun `tracks sharing a language add only what tells them apart`() {
        val labels = TrackLabels.of(
            listOf(
                TrackFacts("English", name = "Signs", forced = true, codec = null),
                TrackFacts("English", name = "Full", forced = false, codec = null),
                TrackFacts("Japanese"),
            ),
        )
        assertEquals(listOf("English · Signs · forced", "English · Full", "Japanese"), labels)

        val audio = TrackLabels.of(
            listOf(
                TrackFacts("English", channels = "5.1", codec = "DD+"),
                TrackFacts("English", channels = "stereo", codec = "DD+"),
            ),
        )
        // Both are DD+, so the codec says nothing here.
        assertEquals(listOf("English · 5.1", "English · stereo"), audio)
    }

    @Test
    fun `alike tracks are numbered within their language`() {
        val labels = TrackLabels.of(listOf(TrackFacts("English"), TrackFacts("English"), TrackFacts("Korean")))
        assertEquals(listOf("English 1", "English 2", "Korean"), labels)
    }

    @Test
    fun `a track without a language is named by its facts or its place`() {
        val labels = TrackLabels.of(
            listOf(
                TrackFacts(null, channels = "5.1"),
                TrackFacts(null, channels = "stereo"),
                TrackFacts("English"),
            ),
        )
        assertEquals(listOf("5.1", "stereo", "English"), labels)
        assertEquals(listOf("Track 1", "English"), TrackLabels.of(listOf(TrackFacts(null), TrackFacts("English"))))
    }
}
