package tv.anstelle.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StreamPlannerTest {
    /** A typical 1080p box: H.264 and 8-bit HEVC in hardware, common audio, no TrueHD. */
    private val box = PlaybackCapabilities(
        video = mapOf("h264" to VideoSupport(1920, 1088, 8), "hevc" to VideoSupport(3840, 2160, 8)),
        audio = setOf("aac", "mp3", "ac3", "eac3", "opus", "pcm"),
        concurrentVideoDecoders = false,
    )

    private fun info(
        container: String? = "mkv",
        video: String? = "h264",
        audio: String? = "aac",
        width: Int? = 1920,
        height: Int? = 1080,
        bitDepth: Int? = 8,
    ) = StreamInfo(container, video, audio, width, height, bitDepth)

    @Test
    fun `files the device decodes play directly`() {
        assertEquals(StreamDecision.DirectPlay, StreamPlanner.decide(info(), box))
        assertEquals(StreamDecision.DirectPlay, StreamPlanner.decide(info(container = "mov,mp4,m4a,3gp,3g2,mj2", video = "HEVC", audio = "EAC3"), box))
        assertEquals(StreamDecision.DirectPlay, StreamPlanner.decide(info(container = "mpegts", audio = "pcm_s24le"), box))
        // No audio at all is fine; unknown sizes count as fitting.
        assertEquals(StreamDecision.DirectPlay, StreamPlanner.decide(info(audio = null, width = null, height = null), box))
    }

    @Test
    fun `anything the device can't handle is transcoded, with the reasons`() {
        fun reasons(info: StreamInfo) = assertIs<StreamDecision.Transcode>(StreamPlanner.decide(info, box)).reasons

        assertEquals(listOf("container asf"), reasons(info(container = "asf")))
        assertEquals(listOf("video codec av1"), reasons(info(video = "av1")))
        assertEquals(listOf("video size 3840x2160"), reasons(info(width = 3840, height = 2160)))
        assertEquals(listOf("video bit depth 10"), reasons(info(video = "hevc", bitDepth = 10)))
        assertEquals(listOf("audio codec truehd"), reasons(info(audio = "truehd")))
        assertEquals(listOf("video codec vc1", "audio codec dts"), reasons(info(video = "vc1", audio = "dts")))
    }
}
