package tv.anstelle.core.scheduler

import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.SlotKind
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NowPlayingTest {
    private val zone = ZoneId.of("UTC")
    private val start = utc(zone, "2026-10-05T20:00:00")
    private val channelId = 7L

    // Two 24-minute episodes back to back: episode 1 at 0–24, episode 2 at 24–48.
    private val slots = run {
        val inputs = ScheduleInputs(
            config = ChannelConfig(seed = 3, playbackOrder = PlaybackOrder.EPISODE_ORDER),
            pool = CandidatePool.of(show("a", 0, 2, 24 * MIN)),
            zone = zone,
        )
        ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + 25 * MIN).slots
    }

    private fun at(offsetMs: Long) = slots.nowPlaying(channelId, start + offsetMs)

    @Test
    fun programStartPlaysFromTheBeginning() {
        val now = assertNotNull(at(0))
        assertEquals(SlotKind.PROGRAM, now.kind)
        assertEquals(1L, now.mediaItemId)
        assertEquals(0L, now.mediaPositionMs)
        assertEquals(channelId, now.channelId)
        assertEquals(start, now.slotStartUtcMs)
        assertEquals(start + 24 * MIN, now.slotEndUtcMs)
    }

    @Test
    fun positionAdvancesWithTheClock() {
        assertEquals(5 * MIN + 1234, at(5 * MIN + 1234)!!.mediaPositionMs)
        assertEquals(24 * MIN - 1, at(24 * MIN - 1)!!.mediaPositionMs)
    }

    @Test
    fun nextEpisodeStartsExactlyWhenThePreviousEnds() {
        val now = assertNotNull(at(24 * MIN))
        assertEquals(2L, now.mediaItemId)
        assertEquals(0L, now.mediaPositionMs)
        assertEquals(24 * MIN - 1, at(48 * MIN - 1)!!.mediaPositionMs)
    }

    @Test
    fun outsideTheScheduleIsNothing() {
        assertNull(at(-1))
        assertNull(at(48 * MIN))
        assertEquals(-1, slots.indexAt(start + 48 * MIN))
    }

    @Test
    fun offAirHasNoItem() {
        val inputs = ScheduleInputs(
            config = ChannelConfig(seed = 3, playbackOrder = PlaybackOrder.SHUFFLE),
            pool = CandidatePool.EMPTY,
            zone = zone,
        )
        val offAir = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + 1).slots
        val now = assertNotNull(offAir.nowPlaying(channelId, start + 10 * MIN))
        assertEquals(SlotKind.OFF_AIR, now.kind)
        assertNull(now.mediaItemId)
        assertEquals(10 * MIN, now.mediaPositionMs)
    }

    @Test
    fun storedSlotResolvesTheSameWay() {
        val i = slots.indexAt(start + 30 * MIN)
        val stored = nowPlaying(
            channelId, slots.kind(i), slots.mediaItemId(i), slots.startUtcMs(i), slots.endUtcMs(i), start + 30 * MIN,
        )
        assertEquals(at(30 * MIN), stored)
        assertNull(nowPlaying(channelId, SlotKind.PROGRAM, 1, 0, 10, 10))
    }
}
