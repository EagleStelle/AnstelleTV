package tv.anstelle.core.data.playback

import tv.anstelle.core.model.WatchSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchRulesTest {
    private val second = 1_000L
    private val minute = 60 * second
    private val episode = 22 * minute

    /** Plays [fromMs] to [toMs] with a sample every second, like the channel player's tick. */
    private fun WatchCoverage.play(fromMs: Long, toMs: Long) {
        var position = fromMs
        while (position <= toMs) {
            sample(position)
            position += second
        }
    }

    @Test
    fun `surfing past a channel isn't watching it`() {
        val surfed = WatchCoverage(episode).apply { play(5 * minute, 5 * minute + 10 * second) }
        assertFalse(WatchRules.channelWatching(surfed.coveredMs))
        // Seeking about doesn't add up to watching.
        val skipped = WatchCoverage(episode).apply { listOf(0L, 10L, 20L, 30L, 40L).forEach { sample(it * second) } }
        assertFalse(WatchRules.channelWatching(skipped.coveredMs))
        val stayed = WatchCoverage(episode).apply { play(5 * minute, 5 * minute + 30 * second) }
        assertTrue(WatchRules.channelWatching(stayed.coveredMs))
    }

    @Test
    fun `a program watched from the start counts`() {
        val coverage = WatchCoverage(episode).apply { play(0, episode) }
        assertTrue(WatchRules.watchedThrough(coverage.coveredMs, episode))
    }

    @Test
    fun `joining late or leaving early doesn't count`() {
        val late = WatchCoverage(episode).apply { play(3 * minute, episode) }
        assertFalse(WatchRules.watchedThrough(late.coveredMs, episode))
        val early = WatchCoverage(episode).apply { play(0, episode / 2) }
        assertFalse(WatchRules.watchedThrough(early.coveredMs, episode))
        // Joined a minute in, left as the credits rolled: 90% played.
        val credits = WatchCoverage(episode).apply { play(minute, episode - minute + 12 * second) }
        assertTrue(WatchRules.watchedThrough(credits.coveredMs, episode))
    }

    @Test
    fun `seeking past a part doesn't count it`() {
        val coverage = WatchCoverage(episode).apply {
            play(0, 5 * minute)
            play(15 * minute, episode)
        }
        assertFalse(WatchRules.watchedThrough(coverage.coveredMs, episode))
    }

    @Test
    fun `starting over fills in what was missed`() {
        val coverage = WatchCoverage(episode).apply {
            play(10 * minute, 12 * minute)
            play(0, episode)
        }
        assertTrue(WatchRules.watchedThrough(coverage.coveredMs, episode))
    }

    @Test
    fun `a pause joins up, a jump doesn't`() {
        val coverage = WatchCoverage(episode).apply {
            sample(0)
            sample(0) // paused in between
            sample(2 * second)
            sample(30 * second)
        }
        assertEquals(4 * second, coverage.coveredMs)
    }

    @Test
    fun `on demand counts stopping past 90 percent`() {
        assertTrue(WatchRules.reachedEnd(episode * 9 / 10, episode))
        assertFalse(WatchRules.reachedEnd(episode / 2, episode))
        assertFalse(WatchRules.reachedEnd(0, 0))
    }

    @Test
    fun `live tv marks a watched program and otherwise puts the mark back when progress is saved`() {
        assertEquals(AfterStop.MARK_WATCHED, WatchRules.afterStop(WatchSync.STATUS, watched = true, onDemand = false))
        assertEquals(AfterStop.NOTHING, WatchRules.afterStop(WatchSync.STATUS, watched = false, onDemand = false))
        assertEquals(AfterStop.RESTORE_PLAYED, WatchRules.afterStop(WatchSync.PROGRESS, watched = true, onDemand = false))
        assertEquals(AfterStop.MARK_WATCHED, WatchRules.afterStop(WatchSync.BOTH, watched = true, onDemand = false))
        assertEquals(AfterStop.RESTORE_PLAYED, WatchRules.afterStop(WatchSync.BOTH, watched = false, onDemand = false))
        assertEquals(AfterStop.NOTHING, WatchRules.afterStop(WatchSync.OFF, watched = true, onDemand = false))
    }

    @Test
    fun `on demand with both marks a finished view and otherwise leaves Jellyfin's marks alone`() {
        assertEquals(AfterStop.MARK_WATCHED, WatchRules.afterStop(WatchSync.BOTH, watched = true, onDemand = true))
        assertEquals(AfterStop.NOTHING, WatchRules.afterStop(WatchSync.BOTH, watched = false, onDemand = true))
        assertEquals(AfterStop.MARK_WATCHED, WatchRules.afterStop(WatchSync.STATUS, watched = true, onDemand = true))
        assertEquals(AfterStop.RESTORE_PLAYED, WatchRules.afterStop(WatchSync.PROGRESS, watched = true, onDemand = true))
        assertFalse(WatchRules.needsPlayedBefore(WatchSync.BOTH, onDemand = true))
        assertTrue(WatchRules.needsPlayedBefore(WatchSync.BOTH, onDemand = false))
        assertFalse(WatchRules.needsPlayedBefore(WatchSync.STATUS, onDemand = false))
    }
}
