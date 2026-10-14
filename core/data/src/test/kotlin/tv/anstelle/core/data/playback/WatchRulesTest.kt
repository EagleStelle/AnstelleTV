package tv.anstelle.core.data.playback

import tv.anstelle.core.data.stats.PlayClock
import tv.anstelle.core.model.WatchSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchRulesTest {
    private val episode = 22 * 60_000L

    @Test
    fun `late join counts at the ending position after thirty seconds`() {
        assertTrue(WatchRules.completed(episode * 95 / 100, episode, false, false, 30_000))
        assertFalse(WatchRules.completed(episode * 95 / 100, episode, false, false, 29_999))
        assertFalse(WatchRules.completed(episode, episode, true, false, 10_000))
    }

    @Test
    fun `natural end counts even when runtime metadata is wrong`() {
        assertTrue(WatchRules.completed(60_000, episode, true, false, 30_000))
        assertTrue(WatchRules.completed(60_000, episode, true, true, 1_000))
        assertFalse(WatchRules.completed(60_000, episode, false, true, 60_000))
    }

    @Test
    fun `completion uses Jellyfin default ninety percent`() {
        assertTrue(WatchRules.reachedEnd(episode * 9 / 10, episode))
        assertFalse(WatchRules.reachedEnd(episode * 9 / 10 - 1, episode))
        assertFalse(WatchRules.reachedEnd(0, 0))
    }

    @Test
    fun `pauses buffering and seeks cannot satisfy thirty seconds`() {
        val clock = PlayClock()
        clock.set(true, 0)
        clock.set(false, 10_000)
        assertFalse(WatchRules.channelWatching(clock.playedMsAt(90_000)))
        clock.set(true, 90_000)
        assertFalse(WatchRules.channelWatching(clock.playedMsAt(109_999)))
        assertTrue(WatchRules.channelWatching(clock.playedMsAt(110_000)))
    }

    @Test
    fun `progress and off never mark watched on either player`() {
        for (onDemand in listOf(false, true)) {
            for (sync in listOf(WatchSync.PROGRESS, WatchSync.OFF)) {
                assertEquals(AfterStop.NOTHING, WatchRules.afterStop(sync, true, onDemand))
                assertFalse(WatchRules.needsPlayedBefore(sync, onDemand))
            }
            assertEquals(AfterStop.MARK_WATCHED, WatchRules.afterStop(WatchSync.STATUS, true, onDemand))
            assertEquals(AfterStop.NOTHING, WatchRules.afterStop(WatchSync.STATUS, false, onDemand))
        }
    }
}
