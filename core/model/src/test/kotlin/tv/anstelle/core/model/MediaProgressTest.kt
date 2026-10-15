package tv.anstelle.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaProgressTest {
    @Test fun unstartedAndUnknownDurationHaveNoProgressOrTimeLeft() {
        for (progress in listOf(MediaProgress(1_440_000, 0), MediaProgress(0, 60_000), MediaProgress(1_440_000, -1))) {
            assertFalse(progress.hasProgress)
            assertNull(progress.remainingLabel)
            assertEquals(0f, progress.fraction)
        }
    }

    @Test fun startedEpisodesShareRoundedTimeLeft() {
        val progress = MediaProgress(1_440_000, 720_001)
        assertTrue(progress.hasProgress)
        assertEquals("12 MIN", progress.remainingLabel)
        assertEquals("1 H 4 MIN", MediaProgress.remainingTime(3_840_000))
        assertEquals("1 MIN", MediaProgress(1_440_000, 1_439_999).remainingLabel)
    }

    @Test fun finishedAndOvershotDurationsClampAtZero() {
        for (position in listOf(1_440_000L, 1_500_000L)) {
            val progress = MediaProgress(1_440_000, position)
            assertEquals(1f, progress.fraction)
            assertEquals(0L, progress.remainingMs)
            assertEquals("0 MIN", progress.remainingLabel)
        }
    }
}
