package tv.anstelle.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SegmentsTest {
    private val recap = MediaSegment(SegmentType.RECAP, 0, 45_000)
    private val intro = MediaSegment(SegmentType.INTRO, 60_000, 150_000)
    private val outro = MediaSegment(SegmentType.OUTRO, 1_300_000, 1_380_000)
    private val segments = listOf(recap, intro, outro)

    @Test
    fun findsTheSegmentPlaying() {
        assertEquals(recap, segments.playingAt(0))
        assertEquals(intro, segments.playingAt(60_000))
        assertEquals(intro, segments.playingAt(100_000))
        assertEquals(outro, segments.playingAt(1_350_000))
    }

    @Test
    fun nothingBetweenSegments() {
        assertNull(segments.playingAt(50_000))
        assertNull(segments.playingAt(600_000))
        assertNull(emptyList<MediaSegment>().playingAt(0))
    }

    @Test
    fun lastSecondDoesNotCount() {
        assertEquals(intro, segments.playingAt(150_000 - SEGMENT_TAIL_MS - 1))
        assertNull(segments.playingAt(150_000 - SEGMENT_TAIL_MS))
        assertNull(segments.playingAt(150_000))
    }

    @Test
    fun firstOfOverlappingWins() {
        val preview = MediaSegment(SegmentType.PREVIEW, 1_360_000, 1_400_000)
        assertEquals(outro, listOf(outro, preview).playingAt(1_365_000))
        assertEquals(preview, listOf(outro, preview).playingAt(1_390_000))
    }
}
