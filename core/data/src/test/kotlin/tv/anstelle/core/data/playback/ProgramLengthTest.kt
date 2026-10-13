package tv.anstelle.core.data.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramLengthTest {
    private val second = 1_000L
    private val minute = 60 * second
    private val start = 1_791_000_000_000L

    /** Samples the player at [positionsMs], one a second from [fromUtcMs], like the channel player's tick. */
    private fun samples(fromUtcMs: Long, vararg positionsMs: Long): ProgramLength.Sample? =
        positionsMs.foldIndexed(null as ProgramLength.Sample?) { i, last, position -> ProgramLength.sample(last, position, fromUtcMs + i * second) }

    @Test
    fun `a program seen playing up to its end tells its length`() {
        val last = samples(start, 23 * minute + 57 * second, 23 * minute + 58 * second, 23 * minute + 59 * second)
        assertEquals(24 * minute - 500, ProgramLength.ended(last, start + 2 * second + 500))
    }

    @Test
    fun `a program not seen playing up to its end doesn't`() {
        // One sample after a tune can't tell playing from standing still.
        assertNull(ProgramLength.ended(samples(start, 10 * second), start + 500))
        // A start past the file's end: the position stands at the asked-for spot until it ends.
        assertNull(ProgramLength.ended(samples(start, 3 * 60 * minute, 3 * 60 * minute), start + 1_500))
        // A seek just before the end jumps further than a second plays.
        assertNull(ProgramLength.ended(samples(start, 10 * minute, 23 * minute), start + 1_500))
        // A sample from long before the end, the player stalled since.
        assertNull(ProgramLength.ended(samples(start, 10 * second, 11 * second), start + 10 * second))
        assertNull(ProgramLength.ended(null, start))
    }

    @Test
    fun `a file shorter than its airing ends the airing now`() {
        // The server says 6 hours; the file ended 24 minutes in.
        val end = start + 6 * 60 * minute
        assertEquals(start + 24 * minute, ProgramLength.realEnd(start, end, start + 24 * minute, 24 * minute))
        // Also when it wasn't seen up to its end, say tuned in past it.
        assertEquals(start + 3 * 60 * minute, ProgramLength.realEnd(start, end, start + 3 * 60 * minute, null))
    }

    @Test
    fun `a file longer than its airing ends it as long after its start`() {
        // The server says 1 minute; the file played 23, and a stall made it end 10 s later still.
        assertEquals(start + 23 * minute, ProgramLength.realEnd(start, start + minute, start + 23 * minute + 10 * second, 23 * minute))
    }

    @Test
    fun `an airing that ended on time or late by a stall stays`() {
        val end = start + 24 * minute
        assertNull(ProgramLength.realEnd(start, end, end - 2 * second, 24 * minute - 2 * second))
        assertNull(ProgramLength.realEnd(start, end, end + 30 * second, 24 * minute))
        assertNull(ProgramLength.realEnd(start, end, end + 30 * second, null))
        // A program ahead of its airing is left to the hand-off before it.
        assertNull(ProgramLength.realEnd(start, end, start - minute, null))
    }

    @Test
    fun `only a length well off the server's is kept`() {
        assertTrue(ProgramLength.differs(24 * minute, 6 * 60 * minute))
        assertTrue(ProgramLength.differs(23 * minute, minute))
        assertFalse(ProgramLength.differs(24 * minute + 900, 24 * minute))
    }
}
