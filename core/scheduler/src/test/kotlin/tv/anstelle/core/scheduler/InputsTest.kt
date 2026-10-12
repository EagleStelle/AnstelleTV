package tv.anstelle.core.scheduler

import tv.anstelle.core.model.Playback
import tv.anstelle.core.model.PlaybackOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputsTest {
    @Test
    fun monthDayRangeWrapsTheYear() {
        val holidays = MonthDayRange(12, 20, 1, 5)
        assertFalse(holidays.contains(12, 19))
        assertTrue(holidays.contains(12, 20))
        assertTrue(holidays.contains(12, 31))
        assertTrue(holidays.contains(1, 1))
        assertTrue(holidays.contains(1, 5))
        assertFalse(holidays.contains(1, 6))
        assertFalse(holidays.contains(7, 1))
    }

    @Test
    fun monthDayRangeInsideOneYear() {
        val october = MonthDayRange(10, 1, 10, 31)
        assertTrue(october.contains(10, 15))
        assertFalse(october.contains(9, 30))
        assertFalse(october.contains(11, 1))
        assertTrue(MonthDayRange(2, 29, 2, 29).contains(2, 29))
    }

    @Test
    fun daypartWindows() {
        val evening = Daypart(18 * 60, 21 * 60)
        assertTrue(evening.contains(18 * 60))
        assertTrue(evening.contains(21 * 60 - 1))
        assertFalse(evening.contains(21 * 60))

        val overnight = Daypart(22 * 60, 6 * 60)
        assertTrue(overnight.contains(23 * 60))
        assertTrue(overnight.contains(0))
        assertTrue(overnight.contains(6 * 60 - 1))
        assertFalse(overnight.contains(6 * 60))
        assertFalse(overnight.contains(12 * 60))

        assertTrue(Daypart(300, 300).contains(1000))
    }

    @Test
    fun daypartRatingLimitsExcludeUnratedItems() {
        val pool = CandidatePool.of(
            listOf(
                Candidate(id = 1, durationMs = MIN, ratingScore = 0),
                Candidate(id = 2, durationMs = MIN, ratingScore = 12),
                Candidate(id = 3, durationMs = MIN),
            ),
        )
        val kids = Daypart(0, 60, maxRatingScore = 7)
        assertEquals(listOf(true, false, false), List(3) { kids.allows(pool, it) })
        val allowList = Daypart(0, 60, allowedItemIds = longArrayOf(3, 1))
        assertEquals(listOf(true, false, true), List(3) { allowList.allows(pool, it) })
    }

    @Test
    fun poolDropsUnplayableItemsAndRejectsDuplicates() {
        val pool = CandidatePool.of(
            listOf(
                Candidate(id = 1, durationMs = 0),
                Candidate(id = 2, durationMs = MIN, weight = 0f),
                Candidate(id = 3, durationMs = MIN),
            ),
        )
        assertEquals(1, pool.size)
        assertEquals(3L, pool.id(0))
        assertFailsWith<IllegalArgumentException> {
            CandidatePool.of(listOf(Candidate(id = 1, durationMs = MIN), Candidate(id = 1, durationMs = MIN)))
        }
    }

    @Test
    fun showWeightIsTheHighestEpisodeWeight() {
        val pool = CandidatePool.of(
            listOf(
                Candidate(id = 1, durationMs = MIN, showKey = "a", episode = 1, weight = 1f),
                Candidate(id = 2, durationMs = MIN, showKey = "a", episode = 2, weight = 3f),
                Candidate(id = 3, durationMs = MIN, weight = 2f),
            ),
        )
        assertEquals(2, pool.showCount)
        assertContentEquals(doubleArrayOf(3.0, 2.0), pool.showWeights)
    }

    @Test
    fun moviesFollowEpisodesByReleaseDate() {
        val pool = CandidatePool.of(
            listOf(
                Candidate(id = 1, durationMs = MIN, releaseUtcMs = 300),
                Candidate(id = 2, durationMs = MIN),
                Candidate(id = 3, durationMs = MIN, showKey = "a", episode = 1),
                Candidate(id = 4, durationMs = MIN, releaseUtcMs = 100),
                Candidate(id = 5, durationMs = MIN, releaseUtcMs = 100),
            ),
        )
        assertEquals(listOf(3L, 4, 5, 1, 2), List(pool.size) { pool.id(it) })
        assertEquals(listOf(false, true, true, true, true), List(pool.size) { pool.isMovie(it) })
    }

    @Test
    fun configRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { Playback(PlaybackOrder.BLOCKS, 5) }
        assertFailsWith<IllegalArgumentException> { Playback(PlaybackOrder.BLOCKS, 1) }
        assertFailsWith<IllegalArgumentException> { Playback(PlaybackOrder.SHUFFLE, 2) }
        assertFailsWith<IllegalArgumentException> { ChannelConfig(seed = 1, movieGapMs = -1) }
        assertFailsWith<IllegalArgumentException> { SeasonalPool(0, MonthDayRange(1, 1, 1, 2), CandidatePool.EMPTY) }
    }

    @Test
    fun engineStateRoundTrips() {
        val state = EngineState(
            nextStartUtcMs = 1_791_000_000_000,
            rngState = -42,
            showCursors = mapOf("b" to EpisodeKey(2, 5, 77), "a" to EpisodeKey(Int.MAX_VALUE, Int.MAX_VALUE, 9)),
            movieCursor = ReleaseKey(Long.MAX_VALUE, 12),
            decks = mapOf(
                DeckKey(0, -1, movies = false) to DeckState(11, 12, EngineState.NO_ID, 3),
                DeckKey(0, -1, movies = true) to DeckState(13, 14, 15, 2, listOf(DeckHold(15, 3_600_000), DeckHold(16, 1))),
                DeckKey(5, 1, movies = false) to DeckState(21, 22, 23, 0),
            ),
            blockShow = "a",
            blockRemaining = 2,
            lastItemId = 15,
            movieStarts = mapOf(15L to 1_790_990_000_000, 16L to 1_790_995_000_000),
        )
        assertEquals(state, EngineState.decode(state.encode()))
        assertEquals(EngineState(5, 6), EngineState.decode(EngineState(5, 6).encode()))
        assertFailsWith<IllegalArgumentException> { EngineState.decode(byteArrayOf(9)) }
    }

    @Test
    fun shiftingMovesEveryTime() {
        val state = EngineState(nextStartUtcMs = 1_000, rngState = 1, movieStarts = mapOf(7L to 400))
        assertEquals(EngineState(nextStartUtcMs = 1_250, rngState = 1, movieStarts = mapOf(7L to 650)), state.shifted(250))
    }
}
