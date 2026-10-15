package tv.anstelle.core.scheduler

import tv.anstelle.core.model.Playback
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.SlotKind
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScheduleEngineTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val start = utc(manila, "2026-10-05T20:00:00")

    private val library = show("a", 100, 12, 24 * MIN + 13_000) +
        show("b", 200, 7, 22 * MIN, weight = 2f) +
        show("c", 300, 30, 45 * MIN + 1_500) +
        listOf(movie(900, 95 * MIN, year = 1999), movie(901, 128 * MIN + 7_000, year = 1984))

    /** [order] for series and movies alike unless [movies] says otherwise. */
    private fun inputs(
        order: PlaybackOrder,
        seed: Long = 42,
        candidates: List<Candidate> = library,
        seasons: List<SeasonalPool> = emptyList(),
        dayparts: List<Daypart> = emptyList(),
        zone: ZoneId = manila,
        blockSize: Int = 3,
        movies: Playback = playback(order, blockSize),
        movieGapMs: Long = 0,
    ) = ScheduleInputs(
        config = ChannelConfig(seed = seed, series = playback(order, blockSize), movies = movies, movieGapMs = movieGapMs),
        pool = CandidatePool.of(candidates),
        seasons = seasons,
        dayparts = dayparts,
        zone = zone,
    )

    private fun playback(order: PlaybackOrder, blockSize: Int = 3) = Playback(order, if (order == PlaybackOrder.BLOCKS) blockSize else 1)

    private fun generate(inputs: ScheduleInputs, days: Int, from: Long = start): ScheduleResult =
        ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, from), from + days * DAY)

    /** The first [count] items of [order], played round and round from [first]. */
    private fun <T> cycle(order: List<T>, first: T, count: Int): List<T> {
        val opening = order.indexOf(first)
        assertTrue(opening >= 0, "$first is not in $order")
        return List(count) { order[(opening + it) % order.size] }
    }

    /** Every pairing of a series playback with a movie playback. */
    private val everyPlayback = PlaybackOrder.entries.flatMap { series -> PlaybackOrder.entries.map { movies -> series to movies } }

    // region Determinism and resuming

    @Test
    fun sameInputsGiveIdenticalSchedules() {
        for (order in PlaybackOrder.entries) {
            val a = generate(inputs(order, movieGapMs = DAY), 7)
            val b = generate(inputs(order, movieGapMs = DAY), 7)
            assertEquals(a.slots.rows(), b.slots.rows(), "$order")
            assertContentEquals(a.state.encode(), b.state.encode(), "$order")
        }
    }

    @Test
    fun differentSeedGivesDifferentSchedule() {
        for (order in PlaybackOrder.entries) {
            val a = generate(inputs(order, seed = 1), 7)
            val b = generate(inputs(order, seed = 2), 7)
            assertNotEquals(a.slots.programIds(), b.slots.programIds(), "$order")
        }
    }

    @Test
    fun inputOrderDoesNotMatter() {
        val a = generate(inputs(PlaybackOrder.SHUFFLE), 3)
        val b = generate(inputs(PlaybackOrder.SHUFFLE, candidates = library.reversed()), 3)
        assertEquals(a.slots.rows(), b.slots.rows())
    }

    @Test
    fun thirtyDaysEqualTenPlusTwenty() {
        val holidays = SeasonalPool(7, MonthDayRange(10, 20, 10, 25), CandidatePool.of(show("x", 700, 5) + movies(7000, 3)))
        val kids = Daypart(6 * 60, 9 * 60, maxRatingScore = 7)
        val kidsLibrary = library + show("k", 800, 10, 11 * MIN, rating = 0) + movies(8000, 20, rating = 0)
        for ((series, movies) in everyPlayback) {
            for (gap in listOf(0L, DAY)) {
                val inputs = inputs(series, candidates = kidsLibrary, seasons = listOf(holidays), dayparts = listOf(kids), movies = playback(movies, 2), movieGapMs = gap)
                val whole = generate(inputs, 30)

                val first = generate(inputs, 10)
                val resumed = EngineState.decode(first.state.encode())
                val rest = ScheduleEngine.generate(inputs, resumed, start + 30 * DAY)

                assertEquals(whole.slots.rows(), first.slots.rows() + rest.slots.rows(), "$series, $movies, gap $gap")
                assertEquals(whole.state, rest.state, "$series, $movies, gap $gap")
            }
        }
    }

    @Test
    fun initialStateStartsExactlyAtTheGivenTime() {
        val inputs = inputs(PlaybackOrder.SHUFFLE)
        val from = utc(manila, "2026-10-05T20:47:13")
        assertEquals(from, ScheduleEngine.initialState(inputs, from).nextStartUtcMs)
        assertEquals(from, generate(inputs, 1, from).slots.startUtcMs(0))
    }

    @Test
    fun staggeredStartsCatchTheFirstProgramMidway() {
        val now = utc(manila, "2026-10-05T20:47:13")
        val elapsed = (1L..200L).map { seed ->
            for (order in PlaybackOrder.entries) {
                val inputs = inputs(order, seed = seed)
                val state = ScheduleEngine.staggered(inputs, ScheduleEngine.initialState(inputs, now), now)
                assertEquals(state, ScheduleEngine.staggered(inputs, ScheduleEngine.initialState(inputs, now), now))
                val slots = ScheduleEngine.generate(inputs, state, now + DAY).slots
                assertEquals(state.nextStartUtcMs, slots.startUtcMs(0))
                assertTrue(slots.startUtcMs(0) <= now && now < slots.endUtcMs(0), "$order, seed $seed")
            }
            val inputs = inputs(PlaybackOrder.SHUFFLE, seed = seed)
            val slots = ScheduleEngine.generate(inputs, ScheduleEngine.staggered(inputs, ScheduleEngine.initialState(inputs, now), now), now + 1).slots
            (now - slots.startUtcMs(0)).toDouble() / (slots.endUtcMs(0) - slots.startUtcMs(0))
        }
        // Spread over the whole program, not bunched at its start.
        assertTrue(elapsed.min() < 0.1 && elapsed.max() > 0.9, "$elapsed")
        assertTrue(elapsed.count { it < 0.5 } in 60..140, "$elapsed")
    }

    @Test
    fun staggeredStartsNeverMoveBeforeWhatStays() {
        val now = utc(manila, "2026-10-05T20:47:13")
        for (seed in 1L..50L) {
            val inputs = inputs(PlaybackOrder.EPISODE_ORDER, seed = seed)
            val state = ScheduleEngine.staggered(inputs, ScheduleEngine.initialState(inputs, now), now, notBeforeUtcMs = now - MIN)
            assertTrue(state.nextStartUtcMs in now - MIN..now)
        }
    }

    @Test
    fun staggeredStartsOffAirAtNow() {
        val inputs = inputs(PlaybackOrder.EPISODE_ORDER, candidates = emptyList())
        val now = utc(manila, "2026-10-05T20:47:13")
        assertEquals(now, ScheduleEngine.staggered(inputs, ScheduleEngine.initialState(inputs, now), now).nextStartUtcMs)
    }

    // endregion

    @Test
    fun programsRunBackToBackForTheirOwnLength() {
        for (zone in listOf(manila, ZoneId.of("Asia/Kathmandu"), ZoneId.of("America/New_York"))) {
            for (order in PlaybackOrder.entries) {
                val from = utc(zone, "2026-10-28T00:00:00") // covers the US fall-back on Nov 1
                val inputs = inputs(order, zone = zone)
                val slots = generate(inputs, 7, from).slots
                assertWellFormed(slots, inputs, from)
                assertTrue(slots.rows().all { it.kind == SlotKind.PROGRAM })
            }
        }
    }

    // region Playback

    @Test
    fun shuffleDealsEveryItemOncePerDeckWithoutRepeatsAcrossDecks() {
        val episodes = show("s", 0, 9, 25 * MIN)
        val films = movies(1000, 9, 25 * MIN)
        for (candidates in listOf(episodes, films)) {
            val ids = generate(inputs(PlaybackOrder.SHUFFLE, candidates = candidates), 20).slots.programIds()
            val all = candidates.map { it.id }.toSet()
            val decks = ids.chunked(9).filter { it.size == 9 }
            assertTrue(decks.size > 50)
            for (deck in decks) assertEquals(all, deck.toSet())
            assertNoRepeats(ids)
        }
    }

    @Test
    fun episodeOrderPlaysEachSeriesInOrderAndWraps() {
        val ids = generate(inputs(PlaybackOrder.EPISODE_ORDER), 30).slots.programIds()
        val pool = CandidatePool.of(library)
        for (show in 0 until pool.showCount) {
            if (pool.showKeys[show] == null) continue
            val episodes = (pool.showStart[show] until pool.showEnd[show]).map { pool.id(it) }
            val played = ids.filter { it in episodes }
            assertTrue(played.size > episodes.size, "show $show should wrap")
            assertEquals(cycle(episodes, played.first(), played.size), played)
        }
    }

    @Test
    fun seriesOpenAtAnEpisodeOfTheirOwnThenPlayInOrder() {
        val candidates = show("s", 0, 20, 30 * MIN)
        for (order in listOf(PlaybackOrder.EPISODE_ORDER, PlaybackOrder.BLOCKS)) {
            val openings = (1L..100L).map { seed ->
                val ids = generate(inputs(order, seed = seed, candidates = candidates, blockSize = 2), 1).slots.programIds()
                assertEquals(cycle(candidates.map { it.id }, ids.first(), ids.size), ids, "$order, seed $seed")
                ids.first()
            }
            assertTrue(openings.toSet().size > 10, "$order: $openings")
        }
    }

    @Test
    fun completedSeriesWaitForUnfinishedSeriesBeforeRestartingAtEpisodeOne() {
        val candidates = show("short", 100, 2, MIN, weight = 1000f) + show("long", 200, 4, MIN)
        for (order in listOf(PlaybackOrder.EPISODE_ORDER, PlaybackOrder.BLOCKS)) {
            val inputs = inputs(order, candidates = candidates)
            val state = EngineState(start, 42, showCursors = mapOf(
                "short" to EpisodeKey(1, 2, 103),
                "long" to EpisodeKey(1, 2, 202),
            ))
            val result = ScheduleEngine.generate(inputs, state, start + 4 * MIN)
            assertEquals(listOf(202L, 203, 204), result.slots.programIds().take(3), "$order")
            assertTrue(result.slots.programIds()[3] in setOf(101L, 201), "$order reruns open at episode 1")
        }
    }

    @Test
    fun aSeriesBlockEndsAtTheFinalEpisodeWhileOtherSeriesRemain() {
        val inputs = inputs(PlaybackOrder.BLOCKS,
            candidates = show("short", 100, 2, MIN) + show("long", 200, 4, MIN), blockSize = 4)
        val state = EngineState(start, 42, showCursors = mapOf(
            "short" to EpisodeKey(1, 2, 102),
            "long" to EpisodeKey(1, 1, 201),
        ), blockShow = "short", blockRemaining = 3, lastItemId = 101)
        val ids = ScheduleEngine.generate(inputs, state, start + 5 * MIN).slots.programIds()
        assertEquals(listOf(102L, 201, 202, 203, 204), ids)
    }

    @Test
    fun seriesAddedAfterSetupStartAtEpisodeOneAndExistingSeriesContinue() {
        for (order in listOf(PlaybackOrder.EPISODE_ORDER, PlaybackOrder.BLOCKS)) {
            val original = inputs(order, candidates = show("old", 100, 20, MIN))
            val first = ScheduleEngine.generate(original, ScheduleEngine.initialState(original, start), start + MIN)
            val changed = inputs(order, candidates = show("old", 100, 20, MIN) + show("new", 200, 10, MIN))
            val resumed = ScheduleEngine.rewind(changed, EngineState.decode(first.state.encode()),
                first.state.nextStartUtcMs, longArrayOf(), aired(first.slots.rows()))
            val ids = ScheduleEngine.generate(changed, resumed, start + 40 * MIN).slots.programIds()
            assertEquals(201L, ids.first { it > 200 }, "$order new series")
            val previous = first.slots.programIds().single()
            assertEquals(if (previous == 120L) 101L else previous + 1, ids.first { it < 200 }, "$order existing series")
        }
    }

    @Test
    fun regenerationKeepsACompletedSeriesWaitingAcrossDiscardedReruns() {
        val candidates = show("short", 100, 2, MIN, weight = 1000f) + show("long", 200, 4, MIN)
        for (order in listOf(PlaybackOrder.EPISODE_ORDER, PlaybackOrder.BLOCKS)) {
            val inputs = inputs(order, candidates = candidates)
            val completedAtCut = EngineState(start, 42, showCursors = mapOf(
                "short" to EpisodeKey(1, 2, 103), "long" to EpisodeKey(1, 2, 202),
            ), lastItemId = 102)
            val future = ScheduleEngine.generate(inputs, completedAtCut, start + 20 * MIN)
            val state = ScheduleEngine.rewind(inputs, future.state, start, future.slots.programIds().toLongArray(),
                listOf(PastAiring(201, start - 2 * MIN), PastAiring(102, start - MIN)))
            val ids = ScheduleEngine.generate(inputs, state, start + 3 * MIN).slots.programIds()
            assertEquals(listOf(202L, 203, 204), ids, "$order")
        }
    }

    @Test
    fun completedSeriesCanReturnWhenOtherUnfinishedSeriesAreIneligible() {
        val candidates = show("short", 100, 2, MIN, rating = 0) + show("long", 200, 4, MIN, rating = 18)
        for (order in listOf(PlaybackOrder.EPISODE_ORDER, PlaybackOrder.BLOCKS)) {
            val inputs = inputs(order, candidates = candidates, dayparts = listOf(Daypart(0, 0, maxRatingScore = 7)))
            val state = EngineState(start, 42, showCursors = mapOf(
                "short" to EpisodeKey(1, 2, 103), "long" to EpisodeKey(1, 2, 202),
            ), lastItemId = 102)
            val result = ScheduleEngine.generate(inputs, state, start + 4 * MIN)
            assertEquals(listOf(101L, 102, 101, 102), result.slots.programIds(), "$order")
            assertEquals(state.showCursors["long"], result.state.showCursors["long"])
        }
    }

    @Test
    fun seriesOpenAtARegularEpisodeBeforeTheirSpecials() {
        val candidates = show("s", 0, 3, 30 * MIN) +
            Candidate(id = 10, durationMs = 30 * MIN, showKey = "s", season = 0, episode = 1) +
            Candidate(id = 11, durationMs = 30 * MIN, showKey = "s")
        val openings = (1L..60L).map { seed ->
            generate(inputs(PlaybackOrder.EPISODE_ORDER, seed = seed, candidates = candidates), 1).slots.programIds().first()
        }
        assertEquals(setOf(1L, 2, 3), openings.toSet())
        // A series of specials alone still opens somewhere.
        val specials = listOf(10L, 11, 12).map { Candidate(id = it, durationMs = 30 * MIN, showKey = "x", season = 0, episode = it.toInt()) }
        val opened = (1L..60L).map { seed -> generate(inputs(PlaybackOrder.EPISODE_ORDER, seed = seed, candidates = specials), 1).slots.programIds().first() }
        assertEquals(setOf(10L, 11, 12), opened.toSet())
    }

    @Test
    fun specialsAndUnnumberedEpisodesSortLast() {
        val candidates = listOf(
            Candidate(id = 1, durationMs = 30 * MIN, showKey = "s", season = 0, episode = 1),
            Candidate(id = 2, durationMs = 30 * MIN, showKey = "s", season = 2, episode = 1),
            Candidate(id = 3, durationMs = 30 * MIN, showKey = "s", season = 1, episode = 2),
            Candidate(id = 4, durationMs = 30 * MIN, showKey = "s", season = 1, episode = 1),
            Candidate(id = 5, durationMs = 30 * MIN, showKey = "s"),
        )
        val ids = generate(inputs(PlaybackOrder.EPISODE_ORDER, candidates = candidates), 1).slots.programIds()
        assertTrue(ids.first() in setOf(4L, 3, 2), "opens at a special: $ids")
        assertEquals(cycle(listOf(4L, 3, 2, 1, 5), ids.first(), 7), ids.take(7))
    }

    @Test
    fun showWeightsSetHowOftenShowsAir() {
        // None finishes during this sample: weights apply among unfinished series.
        val candidates = show("one", 100_000, 10_000, 10 * MIN, weight = 1f) +
            show("two", 200_000, 10_000, 10 * MIN, weight = 2f) +
            show("five", 300_000, 10_000, 10 * MIN, weight = 5f)
        val inputs = inputs(PlaybackOrder.EPISODE_ORDER, candidates = candidates)
        val state = EngineState(start, 42)
        val ids = ScheduleEngine.generate(inputs, state, start + 40 * DAY).slots.programIds()
        assertTrue(ids.size > 5000)
        val share = ids.groupingBy { it / 100_000 }.eachCount().mapValues { it.value.toDouble() / ids.size }
        assertEquals(1.0 / 8, share.getValue(1), 0.02)
        assertEquals(2.0 / 8, share.getValue(2), 0.02)
        assertEquals(5.0 / 8, share.getValue(3), 0.02)
    }

    @Test
    fun weightsSplitAirtimeBetweenSeriesAndMovies() {
        // One series against four movies: a movie is a show of its own, so movies air 4 times in 5.
        val candidates = show("s", 0, 50, 30 * MIN) + movies(1000, 4, 30 * MIN)
        val ids = generate(inputs(PlaybackOrder.EPISODE_ORDER, candidates = candidates, movies = Playback(PlaybackOrder.SHUFFLE)), 40).slots.programIds()
        assertEquals(0.8, ids.count { it >= 1000 }.toDouble() / ids.size, 0.03)
    }

    @Test
    fun blocksPlayConsecutiveEpisodes() {
        val candidates = show("a", 100, 20) + show("b", 200, 20) + show("c", 300, 2)
        for (size in 2..4) {
            val ids = generate(inputs(PlaybackOrder.BLOCKS, candidates = candidates, blockSize = size), 10).slots.programIds()
            // Blocks stop at the finale, including the partial first run from a random opening.
            fun blockLength(id: Long): Int {
                val episodes = if (id / 100 == 3L) 2 else 20
                return minOf(size, episodes - (id % 100).toInt() + 1)
            }
            val runs = ArrayList<MutableList<Long>>()
            for (id in ids) {
                val run = runs.lastOrNull()
                if (run != null && run.size < blockLength(run.first()) && run.last() / 100 == id / 100) {
                    run += id
                } else {
                    runs += mutableListOf(id)
                }
            }
            assertTrue(runs.size > 100)
            for (run in runs.dropLast(1)) {
                assertEquals(blockLength(run.first()), run.size, "block size $size: $run")
                for (k in 1 until run.size) assertEquals(run[k - 1] + 1, run[k], "$run")
            }
        }
    }

    @Test
    fun moviesInOrderFollowReleaseDates() {
        val candidates = listOf(movie(1, 90 * MIN, year = 2001), movie(2, 90 * MIN, year = 1975), movie(3, 90 * MIN, year = 1990), movie(4, 90 * MIN))
        val ids = generate(inputs(PlaybackOrder.SHUFFLE, candidates = candidates, movies = Playback(PlaybackOrder.EPISODE_ORDER)), 2).slots.programIds()
        // By release date; the movie without one comes last.
        assertEquals(cycle(listOf(2L, 3, 1, 4), ids.first(), 8), ids.take(8))
    }

    @Test
    fun moviesInOrderOpenAtAMovieOfTheirOwn() {
        val candidates = (1..12L).map { movie(it, 90 * MIN, year = 1960 + it.toInt()) }
        val openings = (1L..60L).map { seed ->
            val ids = generate(inputs(PlaybackOrder.SHUFFLE, seed = seed, candidates = candidates, movies = Playback(PlaybackOrder.EPISODE_ORDER)), 1).slots.programIds()
            assertEquals(cycle(candidates.map { it.id }, ids.first(), ids.size), ids, "seed $seed")
            ids.first()
        }
        assertTrue(openings.toSet().size > 6, "$openings")
    }

    @Test
    fun movieBlocksAirInRunsByReleaseDate() {
        val candidates = show("s", 0, 40, 22 * MIN, weight = 12f) + (1..12L).map { movie(1000 + it, 90 * MIN, year = 1960 + it.toInt()) }
        for (size in 2..4) {
            val ids = generate(inputs(PlaybackOrder.EPISODE_ORDER, candidates = candidates, movies = Playback(PlaybackOrder.BLOCKS, size)), 20).slots.programIds()
            val runs = ArrayList<MutableList<Long>>()
            for ((i, id) in ids.withIndex()) {
                if (id < 1000) continue
                if (i > 0 && ids[i - 1] >= 1000) runs.last() += id else runs += mutableListOf(id)
            }
            assertTrue(runs.size > 20)
            for (run in runs.dropLast(1)) {
                // A run is one block or several in a row.
                assertEquals(0, run.size % size, "block size $size: $run")
                for (k in 1 until run.size) assertTrue(run[k] == run[k - 1] + 1 || run[k] == 1001L, "$run")
            }
        }
    }

    @Test
    fun seriesAndMoviesKeepTheirOwnPlayback() {
        val candidates = show("a", 100, 6, 22 * MIN) + show("b", 200, 6, 22 * MIN) + (1..6L).map { movie(1000 + it, 90 * MIN, year = 1980 + it.toInt()) }
        val ids = generate(inputs(PlaybackOrder.SHUFFLE, candidates = candidates, movies = Playback(PlaybackOrder.EPISODE_ORDER)), 20).slots.programIds()
        // Every episode once per deck…
        val episodes = ids.filter { it < 1000 }.chunked(12).filter { it.size == 12 }
        assertTrue(episodes.size > 5)
        for (deck in episodes) assertEquals(candidates.filter { it.id < 1000 }.map { it.id }.toSet(), deck.toSet())
        // …and the movies in release order.
        val films = ids.filter { it >= 1000 }
        assertEquals(cycle((1001L..1006L).toList(), films.first(), films.size), films)
    }

    // endregion

    // region Repeats

    @Test
    fun nothingAirsTwiceInARowWhileAnythingElseCan() {
        val lineups = listOf(
            library,
            movies(1000, 2),
            show("one", 0, 1) + movies(1000, 1),
            show("one", 0, 1) + show("two", 10, 1),
            show("one", 0, 1) + show("many", 10, 5) + movies(1000, 1, weight = 5f),
        )
        for (candidates in lineups) {
            for ((series, movies) in everyPlayback) {
                for (gap in listOf(0L, DAY)) {
                    val inputs = inputs(series, candidates = candidates, movies = playback(movies, 2), movieGapMs = gap, blockSize = 2)
                    val ids = generate(inputs, 7).slots.programIds()
                    assertNoRepeats(ids, "$series, $movies, gap $gap, ${candidates.map { it.id }}")
                }
            }
        }
    }

    @Test
    fun aLoneItemStillAirs() {
        for (candidates in listOf(movies(1000, 1), show("one", 0, 1))) {
            for (order in PlaybackOrder.entries) {
                val inputs = inputs(order, candidates = candidates, movieGapMs = DAY)
                val slots = generate(inputs, 2).slots
                assertWellFormed(slots, inputs, start)
                assertTrue(slots.rows().all { it.kind == SlotKind.PROGRAM && it.mediaItemId == candidates.single().id })
            }
        }
    }

    @Test
    fun aMovieWaitsADayWhenTheLineupCanFillIt() {
        // 30 movies of 100 minutes: 50 hours, more than a day.
        val films = movies(1000, 30, 100 * MIN)
        for (order in PlaybackOrder.entries) {
            val inputs = inputs(PlaybackOrder.EPISODE_ORDER, candidates = films, movies = playback(order, 2), movieGapMs = DAY)
            val rows = generate(inputs, 30).slots.rows()
            assertMoviesWait(rows, DAY, "$order")
        }
    }

    @Test
    fun seriesFillTheDayAroundMoviesThatAlreadyAired() {
        val candidates = show("a", 0, 40, 22 * MIN) + show("b", 100, 40, 44 * MIN) + movies(1000, 3, 95 * MIN)
        for ((series, movies) in everyPlayback) {
            val inputs = inputs(series, candidates = candidates, movies = playback(movies, 2), movieGapMs = DAY, blockSize = 2)
            val rows = generate(inputs, 10).slots.rows()
            assertMoviesWait(rows, DAY, "$series, $movies")
            // Each movie comes back about once a day.
            val counts = rows.mapNotNull { it.mediaItemId }.filter { it >= 1000 }.groupingBy { it }.eachCount()
            for (id in 1001L..1003L) assertTrue(counts.getOrDefault(id, 0) in 8..11, "$series, $movies: $counts")
        }
    }

    @Test
    fun tooFewMoviesForADayStillTakeTurns() {
        // Three two-hour movies can't each wait a day; they rotate instead.
        val films = movies(1000, 3, 120 * MIN)
        for (order in PlaybackOrder.entries) {
            val inputs = inputs(PlaybackOrder.EPISODE_ORDER, candidates = films, movies = playback(order, 2), movieGapMs = DAY)
            val ids = generate(inputs, 10).slots.programIds()
            assertNoRepeats(ids, "$order")
            val counts = ids.groupingBy { it }.eachCount()
            assertTrue(counts.values.all { it >= 30 }, "$order: $counts")
        }
    }

    // endregion

    // region Seasons and dayparts

    @Test
    fun seasonalLineupWrapsTheYear() {
        val holidays = movies(5000, 10, 25 * MIN)
        val season = SeasonalPool(3, MonthDayRange(12, 20, 1, 5), CandidatePool.of(holidays))
        val from = utc(manila, "2026-12-15T00:00:00")
        val inputs = inputs(PlaybackOrder.SHUFFLE, seasons = listOf(season))
        val rows = generate(inputs, 27, from).slots.rows()
        var inSeason = 0
        for (row in rows) {
            val date = local(manila, row.start)
            val expected = season.range.contains(date.monthValue, date.dayOfMonth)
            assertEquals(expected, row.mediaItemId!! > 5000, "$date")
            if (expected) inSeason++
        }
        assertTrue(inSeason > 0 && inSeason < rows.size)
    }

    @Test
    fun daypartsFollowLocalTimeAcrossDst() {
        val newYork = ZoneId.of("America/New_York")
        val candidates = show("kids", 100, 40, 11 * MIN, rating = 0) + show("late", 200, 40, 44 * MIN, rating = 18)
        val evening = Daypart(18 * 60, 21 * 60, maxRatingScore = 7)
        val overnight = Daypart(22 * 60, 5 * 60, minRatingScore = 16) // wraps midnight
        for (from in listOf("2026-03-06T00:00:00", "2026-10-29T00:00:00")) {
            val begin = utc(newYork, from)
            val inputs = inputs(
                PlaybackOrder.EPISODE_ORDER,
                candidates = candidates,
                dayparts = listOf(evening, overnight),
                zone = newYork,
            )
            val result = generate(inputs, 5, begin)
            assertWellFormed(result.slots, inputs, begin)
            var lateOutside = 0
            for (row in result.slots.rows()) {
                val time = local(newYork, row.start)
                val minute = time.hour * 60 + time.minute
                val isKids = row.mediaItemId!! < 200
                when {
                    evening.contains(minute) -> assertTrue(isKids, "late show in the kids window at $time")
                    overnight.contains(minute) -> assertTrue(!isKids, "kids show overnight at $time")
                    !isKids -> lateOutside++
                }
            }
            assertTrue(lateOutside > 0)
        }
    }

    @Test
    fun goesOffAirWhenNothingIsEligible() {
        val candidates = show("late", 200, 10, 44 * MIN, rating = 18)
        val kids = Daypart(6 * 60, 9 * 60 + 15, maxRatingScore = 7)
        val inputs = inputs(PlaybackOrder.SHUFFLE, candidates = candidates, dayparts = listOf(kids))
        val from = utc(manila, "2026-10-06T00:00:00")
        val result = generate(inputs, 2, from)
        assertWellFormed(result.slots, inputs, from)
        for (row in result.slots.rows()) {
            val time = local(manila, row.start)
            val inWindow = kids.contains(time.hour * 60 + time.minute)
            assertEquals(inWindow, row.kind == SlotKind.OFF_AIR, "$time")
        }
        // The 05:52 program runs into the window; the channel is off air from 06:36 and
        // comes back exactly when the window ends.
        val offAir = result.slots.rows().first { it.kind == SlotKind.OFF_AIR }
        assertEquals(utc(manila, "2026-10-06T06:36:00"), offAir.start)
        assertEquals(utc(manila, "2026-10-06T09:15:00"), offAir.end)
    }

    @Test
    fun emptyPoolIsOffAirUntilEachLocalMidnight() {
        val inputs = inputs(PlaybackOrder.EPISODE_ORDER, candidates = emptyList())
        val rows = generate(inputs, 1).slots.rows()
        assertEquals(
            listOf(
                Row(SlotKind.OFF_AIR, null, start, utc(manila, "2026-10-06T00:00:00")),
                Row(SlotKind.OFF_AIR, null, utc(manila, "2026-10-06T00:00:00"), utc(manila, "2026-10-07T00:00:00")),
            ),
            rows,
        )
    }

    // endregion

    // region Regeneration

    @Test
    fun rewindResumesEachSeriesAndTheMoviesAtTheirFirstDiscardedItem() {
        val inputs = inputs(PlaybackOrder.EPISODE_ORDER)
        val result = generate(inputs, 2)
        val rows = result.slots.rows()
        val discarded = rows.drop(10).mapNotNull { it.mediaItemId }
        val cut = rows[9].end

        val state = ScheduleEngine.rewind(inputs, result.state, cut, discarded.toLongArray(), aired(rows.take(10)))
        val regenerated = ScheduleEngine.generate(inputs, state, cut + DAY).slots

        // Every series continues with the episode it would have aired next, and so do the movies.
        val pool = CandidatePool.of(library)
        val groups = (0 until pool.showCount).filter { pool.showKeys[it] != null }
            .map { show -> (pool.showStart[show] until pool.showEnd[show]).map { pool.id(it) }.toSet() } +
            listOf(setOf(900L, 901L))
        for (items in groups) {
            val before = discarded.firstOrNull { it in items } ?: continue
            val after = regenerated.programIds().firstOrNull { it in items } ?: continue
            assertEquals(before, after, "$items")
        }
        assertEquals(cut, regenerated.startUtcMs(0))
    }

    @Test
    fun rewindSkipsDeletedEpisodes() {
        val inputs = inputs(PlaybackOrder.EPISODE_ORDER, candidates = show("a", 0, 10))
        val result = generate(inputs, 1)
        val rows = result.slots.rows()
        val cut = result.slots.startUtcMs(2)
        // The first discarded episode was deleted from the library.
        val deleted = rows[2].mediaItemId!!
        val shrunk = inputs(PlaybackOrder.EPISODE_ORDER, candidates = show("a", 0, 10).filter { it.id != deleted })
        val state = ScheduleEngine.rewind(shrunk, result.state, cut, rows.drop(2).mapNotNull { it.mediaItemId }.toLongArray(), aired(rows.take(2)))
        val next = ScheduleEngine.generate(shrunk, state, cut + DAY).slots.programIds().first()
        assertEquals(deleted % 10 + 1, next)
    }

    @Test
    fun rewindCarriesTheRepeatRulesAcrossTheCut() {
        val films = movies(1000, 30, 100 * MIN)
        for (order in PlaybackOrder.entries) {
            val inputs = inputs(PlaybackOrder.EPISODE_ORDER, candidates = films, movies = playback(order, 2), movieGapMs = DAY)
            val result = generate(inputs, 3)
            val rows = result.slots.rows()
            val kept = rows.take(20)
            val cut = kept.last().end
            // The lineup changed: the regenerated part still keeps the kept movies a day away.
            val changed = inputs(PlaybackOrder.EPISODE_ORDER, seed = 7, candidates = films, movies = playback(order, 2), movieGapMs = DAY)
            val state = ScheduleEngine.rewind(changed, result.state, cut, rows.drop(20).mapNotNull { it.mediaItemId }.toLongArray(), aired(kept))
            val regenerated = ScheduleEngine.generate(changed, state, cut + 3 * DAY).slots.rows()
            assertNotEquals(kept.last().mediaItemId, regenerated.first().mediaItemId)
            assertMoviesWait(kept + regenerated, DAY, "$order")
        }
    }

    // endregion

    @Test
    fun fingerprintTracksInputs() {
        val a = inputs(PlaybackOrder.SHUFFLE)
        assertEquals(a.fingerprint(), inputs(PlaybackOrder.SHUFFLE).fingerprint())
        assertEquals(a.fingerprint(), inputs(PlaybackOrder.SHUFFLE, candidates = library.reversed()).fingerprint())
        assertNotEquals(0L, a.fingerprint())
        val changed = listOf(
            inputs(PlaybackOrder.SHUFFLE, seed = 43),
            inputs(PlaybackOrder.BLOCKS),
            inputs(PlaybackOrder.BLOCKS, blockSize = 2),
            inputs(PlaybackOrder.SHUFFLE, movies = Playback(PlaybackOrder.EPISODE_ORDER)),
            inputs(PlaybackOrder.SHUFFLE, movieGapMs = DAY),
            inputs(PlaybackOrder.SHUFFLE, candidates = library.drop(1)),
            inputs(PlaybackOrder.SHUFFLE, candidates = library.map { if (it.id == 900L) movie(900, it.durationMs, year = 2000) else it }),
            inputs(PlaybackOrder.SHUFFLE, dayparts = listOf(Daypart(0, 60, maxRatingScore = 7))),
            inputs(PlaybackOrder.SHUFFLE, zone = ZoneId.of("Asia/Tokyo")),
        )
        for (other in changed) assertNotEquals(a.fingerprint(), other.fingerprint())
    }

    private fun aired(rows: List<Row>) = rows.filter { it.mediaItemId != null }.map { PastAiring(it.mediaItemId!!, it.start) }

    private fun assertNoRepeats(ids: List<Long>, message: String = "") {
        for (i in 1 until ids.size) assertNotEquals(ids[i - 1], ids[i], "repeat at $i: $message")
    }

    /** No movie (an id of 1000 or more) starts again within [gap] of its last start. */
    private fun assertMoviesWait(rows: List<Row>, gap: Long, message: String) {
        val last = HashMap<Long, Long>()
        for (row in rows) {
            val id = row.mediaItemId ?: continue
            if (id < 1000) continue
            last[id]?.let { previous -> assertTrue(row.start - previous >= gap, "$message: $id again after ${(row.start - previous) / MIN} min") }
            last[id] = row.start
        }
    }
}
