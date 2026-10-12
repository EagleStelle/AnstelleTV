package tv.anstelle.core.scheduler

import tv.anstelle.core.model.Playback
import tv.anstelle.core.model.PlaybackOrder
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

class EnginePerformanceTest {
    /** 100 channels × 30 days, including building each channel's pool, must take under 5 s. */
    @Test
    fun hundredChannelsForThirtyDays() {
        val zone = ZoneId.of("America/New_York")
        val start = utc(zone, "2026-10-20T00:00:00")
        val channels = List(100) { c ->
            // Every channel gets its own library slice: 40 shows of 60 episodes plus 80 movies.
            val candidates = ArrayList<Candidate>()
            for (s in 0 until 40) {
                val minutes = if (s % 3 == 0) 44L else 22L
                candidates += show(
                    key = "show-$c-$s",
                    idBase = c * 1_000_000L + s * 1_000L,
                    count = 60,
                    durationMs = minutes * MIN + s * 1_000L,
                    weight = 1f + s % 4,
                    rating = s % 18,
                )
            }
            for (m in 0 until 80) candidates += Candidate(id = c * 1_000_000L + 900_000 + m, durationMs = (85 + m) * MIN)
            c to candidates
        }

        val began = System.nanoTime()
        var slots = 0L
        for ((c, candidates) in channels) {
            val inputs = ScheduleInputs(
                config = ChannelConfig(
                    seed = c.toLong(),
                    series = playbackOf(c),
                    movies = playbackOf(c / 3),
                    movieGapMs = if (c % 2 == 0) DAY else 0,
                ),
                pool = CandidatePool.of(candidates),
                dayparts = if (c % 2 == 0) listOf(Daypart(6 * 60, 9 * 60, maxRatingScore = 7)) else emptyList(),
                zone = zone,
            )
            val result = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + 30 * DAY)
            result.state.encode()
            slots += result.slots.size
        }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        println("Scheduled 100 channels × 30 days ($slots slots) in $elapsedMs ms")
        assertTrue(elapsedMs < 5_000, "took $elapsedMs ms")
    }

    private fun playbackOf(n: Int): Playback {
        val order = PlaybackOrder.entries[n % 3]
        return Playback(order, if (order == PlaybackOrder.BLOCKS) 2 + n % 3 else 1)
    }
}
