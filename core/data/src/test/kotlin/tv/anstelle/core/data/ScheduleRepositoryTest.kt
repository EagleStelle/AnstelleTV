package tv.anstelle.core.data

import kotlinx.coroutines.test.runTest
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.database.entity.ScheduleStateEntity
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.scheduler.Candidate
import tv.anstelle.core.scheduler.CandidatePool
import tv.anstelle.core.scheduler.ChannelConfig
import tv.anstelle.core.scheduler.ScheduleEngine
import tv.anstelle.core.scheduler.ScheduleInputs
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleRepositoryTest {
    private val minute = 60_000L
    private val start = 1_791_000_000_000L - 1_791_000_000_000L % (30 * minute)
    private val dao = FakeScheduleDao()
    private val repository = ScheduleRepository(dao, TimeSource { start })

    private val inputs = ScheduleInputs(
        config = ChannelConfig(seed = 5, playbackOrder = PlaybackOrder.EPISODE_ORDER),
        pool = CandidatePool.of(
            List(4) {
                Candidate(
                    id = it + 1L,
                    durationMs = 24 * minute,
                    showKey = "s",
                    season = 1,
                    episode = it + 1,
                )
            },
        ),
        zone = ZoneOffset.UTC,
    )

    @Test
    fun savedScheduleAnswersNowPlaying() = runTest {
        val result = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + 2 * 60 * minute)
        repository.save(channelId = 9, fromUtcMs = start, result = result, inputsHash = inputs.fingerprint())

        assertEquals(result.slots.size, dao.slots.size)
        assertEquals(result.slots.nowPlaying(9, start + 5 * minute), repository.nowPlaying(9, start + 5 * minute))
        // Episodes run back to back, so episode 2 starts at 24 min.
        val second = repository.nowPlaying(9, start + 30 * minute)!!
        assertEquals(SlotKind.PROGRAM, second.kind)
        assertEquals(2L, second.mediaItemId)
        assertEquals(start + 24 * minute, second.slotStartUtcMs)
        assertEquals(6 * minute, second.mediaPositionMs)
        assertNull(repository.nowPlaying(9, start - 1))
        assertNull(repository.nowPlaying(10, start + 5 * minute))
    }

    @Test
    fun storedStateResumesTheSchedule() = runTest {
        val first = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + 60 * minute)
        repository.save(9, start, first, inputs.fingerprint())

        val stored = repository.stored(9)!!
        assertEquals(first.state, stored.state)
        assertEquals(first.state.nextStartUtcMs, stored.generatedUntilUtcMs)
        assertEquals(inputs.fingerprint(), stored.inputsHash)
        assertEquals(start, dao.states.getValue(9).updatedAtUtcMs)
    }

    /** (item, start, end) of channel 9's slots, minutes from [start]. */
    private fun layout() = dao.slotsOf(9).map { Triple(it.mediaItemId, (it.startUtcMs - start) / minute, (it.endUtcMs - start) / minute) }

    private suspend fun saveTwoHours() {
        val result = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + 2 * 60 * minute)
        repository.save(9, start, result, inputs.fingerprint())
    }

    @Test
    fun seekingBackMovesTheRestOfTheScheduleLater() = runTest {
        saveTwoHours()
        val until = repository.stored(9)!!.generatedUntilUtcMs
        // Ten minutes into episode 1, the viewer starts it over.
        repository.shift(9, start, 10 * minute)
        assertEquals(
            listOf(Triple(1L, 0L, 10L), Triple(1L, 10L, 34L), Triple(2L, 34L, 58L), Triple(3L, 58L, 82L), Triple(4L, 82L, 106L), Triple(1L, 106L, 130L)),
            layout(),
        )
        assertEquals(1L, repository.nowPlaying(9, start + 10 * minute)!!.mediaItemId)
        assertEquals(0L, repository.nowPlaying(9, start + 10 * minute)!!.mediaPositionMs)
        val stored = repository.stored(9)!!
        assertEquals(until + 10 * minute, stored.generatedUntilUtcMs)
        assertEquals(until + 10 * minute, stored.state.nextStartUtcMs)

        // Five more minutes back: the part already shown stays one airing.
        repository.shift(9, start + 10 * minute, 5 * minute)
        assertEquals(listOf(Triple(1L, 0L, 15L), Triple(1L, 15L, 39L), Triple(2L, 39L, 63L)), layout().take(3))
    }

    @Test
    fun seekingAheadMovesItEarlierOverWhatAiredBefore() = runTest {
        saveTwoHours()
        // Episode 2 began at 24 min; 30 minutes ahead it began at -6, over all of episode 1.
        repository.shift(9, start + 24 * minute, -30 * minute)
        assertEquals(
            listOf(Triple(2L, -6L, 18L), Triple(3L, 18L, 42L), Triple(4L, 42L, 66L), Triple(1L, 66L, 90L)),
            layout(),
        )
        // A smaller step ahead cuts the airing before it short instead.
        repository.shift(9, start + 18 * minute, -4 * minute)
        assertEquals(listOf(Triple(2L, -6L, 14L), Triple(3L, 14L, 38L)), layout().take(2))
        assertEquals(repository.stored(9)!!.state.nextStartUtcMs, dao.slotsOf(9).last().endUtcMs)
    }

    @Test
    fun unreadableStateIsTreatedAsMissing() = runTest {
        dao.upsertState(ScheduleStateEntity(9, start, byteArrayOf(42), 1, start))
        assertNull(repository.stored(9))
        assertNull(repository.stored(10))
    }
}
