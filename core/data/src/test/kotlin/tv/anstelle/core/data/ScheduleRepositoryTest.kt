package tv.anstelle.core.data

import kotlinx.coroutines.test.runTest
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.database.entity.ScheduleStateEntity
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
        config = ChannelConfig(seed = 5),
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

    /** The series opens at an episode picked from the seed. */
    private val opening = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + 1).slots.mediaItemId(0)!!

    /** The [n]th episode to air, counted from 1. */
    private fun ep(n: Int): Long = (opening - 1 + n - 1) % 4 + 1

    @Test
    fun savedScheduleAnswersNowPlaying() = runTest {
        val result = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + 2 * 60 * minute)
        repository.save(channelId = 9, fromUtcMs = start, result = result, inputsHash = inputs.fingerprint())

        assertEquals(result.slots.size, dao.slots.size)
        assertEquals(result.slots.nowPlaying(9, start + 5 * minute), repository.nowPlaying(9, start + 5 * minute))
        // Episodes run back to back, so the second one starts at 24 min.
        val second = repository.nowPlaying(9, start + 30 * minute)!!
        assertEquals(SlotKind.PROGRAM, second.kind)
        assertEquals(ep(2), second.mediaItemId)
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
        // Ten minutes into the first episode, the viewer starts it over.
        repository.shift(9, start, 10 * minute)
        assertEquals(
            listOf(Triple(ep(1), 0L, 10L), Triple(ep(1), 10L, 34L), Triple(ep(2), 34L, 58L), Triple(ep(3), 58L, 82L), Triple(ep(4), 82L, 106L), Triple(ep(5), 106L, 130L)),
            layout(),
        )
        assertEquals(ep(1), repository.nowPlaying(9, start + 10 * minute)!!.mediaItemId)
        assertEquals(0L, repository.nowPlaying(9, start + 10 * minute)!!.mediaPositionMs)
        val stored = repository.stored(9)!!
        assertEquals(until + 10 * minute, stored.generatedUntilUtcMs)
        assertEquals(until + 10 * minute, stored.state.nextStartUtcMs)

        // Five more minutes back: the part already shown stays one airing.
        repository.shift(9, start + 10 * minute, 5 * minute)
        assertEquals(listOf(Triple(ep(1), 0L, 15L), Triple(ep(1), 15L, 39L), Triple(ep(2), 39L, 63L)), layout().take(3))
    }

    @Test
    fun seekingAheadMovesItEarlierOverWhatAiredBefore() = runTest {
        saveTwoHours()
        // The second episode began at 24 min; 30 minutes ahead it began at -6, over all of the first.
        repository.shift(9, start + 24 * minute, -30 * minute)
        assertEquals(
            listOf(Triple(ep(2), -6L, 18L), Triple(ep(3), 18L, 42L), Triple(ep(4), 42L, 66L), Triple(ep(5), 66L, 90L)),
            layout(),
        )
        // A smaller step ahead cuts the airing before it short instead.
        repository.shift(9, start + 18 * minute, -4 * minute)
        assertEquals(listOf(Triple(ep(2), -6L, 14L), Triple(ep(3), 14L, 38L)), layout().take(2))
        assertEquals(repository.stored(9)!!.state.nextStartUtcMs, dao.slotsOf(9).last().endUtcMs)
    }

    @Test
    fun aFileShorterThanItsAiringPullsTheRestEarlier() = runTest {
        saveTwoHours()
        val until = repository.stored(9)!!.generatedUntilUtcMs
        // The second episode's file ran out 4 minutes before its 24-minute airing ended.
        repository.endAt(9, start + 24 * minute, start + 44 * minute)
        assertEquals(
            listOf(Triple(ep(1), 0L, 24L), Triple(ep(2), 24L, 44L), Triple(ep(3), 44L, 68L), Triple(ep(4), 68L, 92L), Triple(ep(5), 92L, 116L)),
            layout(),
        )
        assertEquals(until - 4 * minute, repository.stored(9)!!.state.nextStartUtcMs)
    }

    @Test
    fun aFileLongerThanItsAiringPushesTheRestLater() = runTest {
        saveTwoHours()
        // The first episode's file played 30 minutes.
        repository.endAt(9, start, start + 30 * minute)
        assertEquals(listOf(Triple(ep(1), 0L, 30L), Triple(ep(2), 30L, 54L), Triple(ep(3), 54L, 78L)), layout().take(3))
        assertEquals(repository.stored(9)!!.state.nextStartUtcMs, dao.slotsOf(9).last().endUtcMs)
        // An airing that isn't there, or an end before its start, changes nothing.
        repository.endAt(9, start + minute, start + 40 * minute)
        repository.endAt(9, start, start)
        assertEquals(listOf(Triple(ep(1), 0L, 30L), Triple(ep(2), 30L, 54L)), layout().take(2))
    }

    @Test
    fun unreadableStateIsTreatedAsMissing() = runTest {
        dao.upsertState(ScheduleStateEntity(9, start, byteArrayOf(42), 1, start))
        assertNull(repository.stored(9))
        assertNull(repository.stored(10))
    }
}
