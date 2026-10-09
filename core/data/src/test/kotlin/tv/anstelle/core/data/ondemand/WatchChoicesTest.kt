package tv.anstelle.core.data.ondemand

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchChoicesTest {
    @Test
    fun `an airing's tense follows the clock`() {
        assertEquals(AiringTense.LATER, AiringTense.of(1_000, 2_000, 999))
        assertEquals(AiringTense.ON_NOW, AiringTense.of(1_000, 2_000, 1_000))
        assertEquals(AiringTense.ON_NOW, AiringTense.of(1_000, 2_000, 1_999))
        assertEquals(AiringTense.AIRED, AiringTense.of(1_000, 2_000, 2_000))
    }

    @Test
    fun `spans read in minutes and hours`() {
        assertEquals("12 min", WatchChoices.span(12 * 60_000 + 59_000))
        assertEquals("1 h", WatchChoices.span(60 * 60_000))
        assertEquals("1 h 4 min", WatchChoices.span(64 * 60_000))
        // Time left counts a started minute, and never says nothing is left.
        assertEquals("18 min", WatchChoices.span(17 * 60_000 + 1_000, roundUp = true))
        assertEquals("1 min", WatchChoices.span(500, roundUp = true))
    }

    @Test
    fun `a program not on now plays from the start, worded by when it airs`() {
        assertEquals("Watch now", WatchChoices.fromStart(AiringTense.LATER, 0).title)
        assertEquals("Watch again", WatchChoices.fromStart(AiringTense.AIRED, 0).title)
        assertEquals(WatchKind.PLAY, WatchChoices.fromStart(AiringTense.LATER, 0).kind)
    }

    @Test
    fun `start over stays on the channel, on demand leaves it`() {
        assertEquals(WatchKind.RESTART, WatchChoices.restart.kind)
        val start = WatchChoices.onDemand(isEpisode = true)
        assertEquals(WatchKind.PLAY, start.kind)
        assertEquals("From the start, then the next episodes.", start.detail)
        assertEquals(0L, start.positionMs)
        assertEquals("From the start, off the schedule.", WatchChoices.onDemand(isEpisode = false).detail)
        val point = WatchChoices.resumeOnDemand(12 * 60_000 + 4_000, isEpisode = true)
        assertEquals("Resume on Demand", point.title)
        assertEquals("From 12 min in, then the next episodes.", point.detail)
        assertEquals(12 * 60_000L + 4_000, point.positionMs)
    }

    @Test
    fun `resume on demand waits until it differs from starting on demand`() {
        val early = WatchChoices.onNow(1001, "ABC", startUtcMs = 0, endUtcMs = 30 * 60_000, nowUtcMs = 20_000, isEpisode = true)
        assertEquals(listOf("Watch live", "Start over", "Watch on Demand"), early.map { it.title })
        val later = WatchChoices.onNow(1001, "ABC", startUtcMs = 0, endUtcMs = 30 * 60_000, nowUtcMs = 5 * 60_000, isEpisode = true)
        assertEquals(listOf("Watch live", "Start over", "Watch on Demand", "Resume on Demand"), later.map { it.title })
    }

    @Test
    fun `live and resume choices carry their position and the channel`() {
        val live = WatchChoices.live(3004, "Fright Night", endUtcMs = 18 * 60_000, nowUtcMs = 0)
        assertEquals(WatchKind.LIVE, live.kind)
        assertEquals("3004 Fright Night · 18 min left", live.detail)
        assertEquals("Resume from 31 min in", WatchChoices.resume(31 * 60_000L + 10_000).title)
        assertEquals("Add movie to a channel", WatchChoices.addToChannel(isMovie = true).title)
        assertEquals("Add show to a channel", WatchChoices.addToChannel(isMovie = false).title)
    }
}
