package tv.anstelle.core.data.playback

import tv.anstelle.core.model.WatchReportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchRulesTest {
    private val minute = 60_000L
    private val episode = 22 * minute
    private val movie = 120 * minute

    @Test
    fun `a full view starts near the beginning and reaches 90 percent`() {
        assertTrue(WatchRules.isFullView(joinedAtMs = 0, stoppedAtMs = episode, durationMs = episode))
        assertTrue(WatchRules.isFullView(joinedAtMs = 2 * minute, stoppedAtMs = episode * 9 / 10, durationMs = episode))
        // Joined at minute 3 of a half-hour show: partial.
        assertFalse(WatchRules.isFullView(joinedAtMs = 3 * minute, stoppedAtMs = episode, durationMs = episode))
        // Long items allow joining within the first 5%.
        assertTrue(WatchRules.isFullView(joinedAtMs = 6 * minute, stoppedAtMs = movie, durationMs = movie))
        // Stopped early: partial.
        assertFalse(WatchRules.isFullView(joinedAtMs = 0, stoppedAtMs = episode / 2, durationMs = episode))
        assertFalse(WatchRules.isFullView(joinedAtMs = 0, stoppedAtMs = 0, durationMs = 0))
    }

    @Test
    fun `report-no-mark only undoes marks from partial views of unwatched items`() {
        val mode = WatchReportMode.REPORT_NO_MARK
        assertEquals(AfterStop.UNMARK_IF_PLAYED, WatchRules.afterStop(mode, playedBefore = false, fullView = false))
        assertEquals(AfterStop.NOTHING, WatchRules.afterStop(mode, playedBefore = true, fullView = false))
        assertEquals(AfterStop.RECORD_PLAYED, WatchRules.afterStop(mode, playedBefore = false, fullView = true))
    }

    @Test
    fun `full reporting keeps the server's marks and off does nothing`() {
        assertEquals(AfterStop.RECORD_PLAYED, WatchRules.afterStop(WatchReportMode.FULL, playedBefore = false, fullView = false))
        assertEquals(AfterStop.NOTHING, WatchRules.afterStop(WatchReportMode.OFF, playedBefore = false, fullView = false))
    }
}
