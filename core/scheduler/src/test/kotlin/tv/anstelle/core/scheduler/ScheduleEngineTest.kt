package tv.anstelle.core.scheduler

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
        listOf(Candidate(id = 900, durationMs = 95 * MIN), Candidate(id = 901, durationMs = 128 * MIN + 7_000))

    private fun inputs(
        order: PlaybackOrder,
        seed: Long = 42,
        candidates: List<Candidate> = library,
        seasons: List<SeasonalPool> = emptyList(),
        dayparts: List<Daypart> = emptyList(),
        zone: ZoneId = manila,
        blockSize: Int = 3,
    ) = ScheduleInputs(
        config = ChannelConfig(seed = seed, playbackOrder = order, blockSize = blockSize),
        pool = CandidatePool.of(candidates),
        seasons = seasons,
        dayparts = dayparts,
        zone = zone,
    )

    private fun generate(inputs: ScheduleInputs, days: Int, from: Long = start): ScheduleResult =
        ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, from), from + days * DAY)

    // region Determinism and resuming

    @Test
    fun sameInputsGiveIdenticalSchedules() {
        for (order in PlaybackOrder.entries) {
            val a = generate(inputs(order), 7)
            val b = generate(inputs(order), 7)
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
        val holidays = SeasonalPool(7, MonthDayRange(10, 20, 10, 25), CandidatePool.of(show("x", 700, 5)))
        val kids = Daypart(6 * 60, 9 * 60, maxRatingScore = 7)
        val kidsLibrary = library + show("k", 800, 10, 11 * MIN, rating = 0)
        for (order in PlaybackOrder.entries) {
            val inputs = inputs(order, candidates = kidsLibrary, seasons = listOf(holidays), dayparts = listOf(kids))
            val whole = generate(inputs, 30)

            val first = generate(inputs, 10)
            val resumed = EngineState.decode(first.state.encode())
            val rest = ScheduleEngine.generate(inputs, resumed, start + 30 * DAY)

            assertEquals(whole.slots.rows(), first.slots.rows() + rest.slots.rows(), "$order")
            assertEquals(whole.state, rest.state, "$order")
        }
    }

    @Test
    fun initialStateStartsExactlyAtTheGivenTime() {
        val inputs = inputs(PlaybackOrder.SHUFFLE)
        val from = utc(manila, "2026-10-05T20:47:13")
        assertEquals(from, ScheduleEngine.initialState(inputs, from).nextStartUtcMs)
        assertEquals(from, generate(inputs, 1, from).slots.startUtcMs(0))
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

    // region Playback orders

    @Test
    fun shuffleDealsEveryItemOncePerDeckWithoutRepeatsAcrossDecks() {
        val candidates = (1..9L).map { Candidate(id = it, durationMs = 25 * MIN) }
        val ids = generate(inputs(PlaybackOrder.SHUFFLE, candidates = candidates), 20).slots.programIds()
        val decks = ids.chunked(9).filter { it.size == 9 }
        assertTrue(decks.size > 100)
        for (deck in decks) assertEquals((1..9L).toSet(), deck.toSet())
        for (i in 1 until ids.size) assertNotEquals(ids[i - 1], ids[i], "repeat at $i")
    }

    @Test
    fun episodeOrderPlaysEachShowInOrderAndWraps() {
        val ids = generate(inputs(PlaybackOrder.EPISODE_ORDER), 30).slots.programIds()
        val pool = CandidatePool.of(library)
        for (show in 0 until pool.showCount) {
            val episodes = (pool.showStart[show] until pool.showEnd[show]).map { pool.id(it) }
            val played = ids.filter { it in episodes }
            assertTrue(played.size > episodes.size, "show $show should wrap")
            assertEquals(List(played.size) { episodes[it % episodes.size] }, played)
        }
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
        assertEquals(listOf(4L, 3, 2, 1, 5, 4, 3), ids.take(7))
    }

    @Test
    fun showWeightsSetHowOftenShowsAir() {
        val candidates = show("one", 1000, 50, 10 * MIN, weight = 1f) +
            show("two", 2000, 50, 10 * MIN, weight = 2f) +
            show("five", 3000, 50, 10 * MIN, weight = 5f)
        val ids = generate(inputs(PlaybackOrder.EPISODE_ORDER, candidates = candidates), 40).slots.programIds()
        assertTrue(ids.size > 5000)
        val share = ids.groupingBy { it / 1000 }.eachCount().mapValues { it.value.toDouble() / ids.size }
        assertEquals(1.0 / 8, share.getValue(1), 0.02)
        assertEquals(2.0 / 8, share.getValue(2), 0.02)
        assertEquals(5.0 / 8, share.getValue(3), 0.02)
    }

    @Test
    fun blocksPlayConsecutiveEpisodes() {
        val candidates = show("a", 100, 20) + show("b", 200, 20) + show("c", 300, 2)
        for (size in 1..4) {
            val ids = generate(inputs(PlaybackOrder.BLOCKS, candidates = candidates, blockSize = size), 10).slots.programIds()
            // Show "c" has only two episodes, so its blocks are shorter.
            fun blockLength(id: Long) = if (id / 100 == 3L) minOf(size, 2) else size
            val runs = ArrayList<MutableList<Long>>()
            for (id in ids) {
                val run = runs.lastOrNull()
                if (run != null && run.size < blockLength(id) && run.last() / 100 == id / 100) {
                    run += id
                } else {
                    runs += mutableListOf(id)
                }
            }
            assertTrue(runs.size > 100)
            for (run in runs.dropLast(1)) {
                assertEquals(blockLength(run.first()), run.size, "block size $size: $run")
                // Consecutive episodes, possibly wrapping to episode 1.
                for (k in 1 until run.size) assertTrue(run[k] == run[k - 1] + 1 || run[k] % 100 == 1L, "$run")
            }
        }
    }

    // endregion

    // region Seasons and dayparts

    @Test
    fun seasonalLineupWrapsTheYear() {
        val holidays = (1..10L).map { Candidate(id = 5000 + it, durationMs = 25 * MIN) }
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
    fun rewindResumesEachShowAtItsFirstDiscardedEpisode() {
        val inputs = inputs(PlaybackOrder.EPISODE_ORDER)
        val result = generate(inputs, 2)
        val rows = result.slots.rows()
        val discarded = rows.drop(10).mapNotNull { it.mediaItemId }
        val cut = rows[9].end

        val state = ScheduleEngine.rewind(inputs, result.state, cut, discarded.toLongArray())
        val regenerated = ScheduleEngine.generate(inputs, state, cut + DAY).slots

        // Every show continues with the episode it would have aired next.
        val pool = CandidatePool.of(library)
        for (show in 0 until pool.showCount) {
            val key = pool.showKeys[show] ?: continue
            val episodes = (pool.showStart[show] until pool.showEnd[show]).map { pool.id(it) }.toSet()
            val before = discarded.firstOrNull { it in episodes } ?: continue
            val after = regenerated.programIds().firstOrNull { it in episodes } ?: continue
            assertEquals(before, after, "show $key")
        }
        assertEquals(cut, regenerated.startUtcMs(0))
    }

    @Test
    fun rewindSkipsDeletedEpisodes() {
        val inputs = inputs(PlaybackOrder.EPISODE_ORDER, candidates = show("a", 0, 10))
        val result = generate(inputs, 1)
        val ids = result.slots.programIds()
        val cut = result.slots.startUtcMs(2)
        // Episode 3 (the first discarded one) was deleted from the library.
        val shrunk = inputs(PlaybackOrder.EPISODE_ORDER, candidates = show("a", 0, 10).filter { it.id != 3L })
        val state = ScheduleEngine.rewind(shrunk, result.state, cut, ids.drop(2).toLongArray())
        val next = ScheduleEngine.generate(shrunk, state, cut + DAY).slots.programIds().first()
        assertEquals(4L, next)
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
            inputs(PlaybackOrder.SHUFFLE, candidates = library.drop(1)),
            inputs(PlaybackOrder.SHUFFLE, dayparts = listOf(Daypart(0, 60, maxRatingScore = 7))),
            inputs(PlaybackOrder.SHUFFLE, zone = ZoneId.of("Asia/Tokyo")),
        )
        for (other in changed) assertNotEquals(a.fingerprint(), other.fingerprint())
    }
}
