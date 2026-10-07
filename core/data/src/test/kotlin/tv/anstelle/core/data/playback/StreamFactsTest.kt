package tv.anstelle.core.data.playback

import tv.anstelle.core.model.HdrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamFactsTest {
    @Test
    fun `quality names the size and the dynamic range`() {
        assertEquals("4K Dolby Vision", StreamFacts.quality(3840, 2160, HdrType.DOLBY_VISION))
        assertEquals("1080p", StreamFacts.quality(1920, 1080, HdrType.SDR))
        // Cropped widescreen films are short; the width still says 1080p.
        assertEquals("1080p", StreamFacts.quality(1920, 800, HdrType.SDR))
        assertEquals("720p HDR10", StreamFacts.quality(1280, 720, HdrType.HDR10))
        assertEquals("480p", StreamFacts.quality(720, 480, HdrType.SDR))
        assertNull(StreamFacts.quality(null, null, HdrType.SDR))
    }

    @Test
    fun `video names the codec and a deep bit depth`() {
        assertEquals("HEVC 10-bit", StreamFacts.video("hevc", 10))
        assertEquals("H.264", StreamFacts.video("h264", 8))
        assertEquals("AV1", StreamFacts.video("av1", null))
        assertNull(StreamFacts.video(null, 10))
    }

    @Test
    fun `audio names the codec, Atmos and the channels`() {
        assertEquals("TrueHD Atmos 7.1", StreamFacts.audio("truehd", "Atmos", 8))
        assertEquals("DD+ 5.1", StreamFacts.audio("eac3", "DD+", 6))
        assertEquals("DTS-HD 5.1", StreamFacts.audio("dts", "DTS-HD", 6))
        assertEquals("AAC Stereo", StreamFacts.audio("aac", "AAC", 2))
        assertEquals("Opus", StreamFacts.audio("opus", "Opus", null))
        assertNull(StreamFacts.audio(null, null, null))
    }
}
