package tv.anstelle.feature.guide

import tv.anstelle.core.data.guide.ProgramStrip
import tv.anstelle.core.database.dao.GuideAiring
import tv.anstelle.core.model.SlotKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuideGridStateTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private fun at(h: Int, m: Int) = h * hour + m * minute

    private val now = at(10, 7)

    /** Two visible hours, opening at the current half hour. */
    private fun grid() = GuideGridState(at(10, 0), pxPerMs = 1200f / (2 * hour)).apply {
        widthPx = 1200
        reset(1)
    }

    private val strip = ProgramStrip.fromRows(
        listOf(
            cell(at(9, 40), at(10, 20), 1),
            cell(at(10, 20), at(11, 0), 2),
            cell(at(11, 0), at(12, 50), 3),
            cell(at(12, 50), at(13, 10), 4),
        ),
        "Off air",
    ).getValue(1)

    private fun cell(start: Long, end: Long, item: Long) =
        GuideAiring(1, SlotKind.PROGRAM, item, start, end, "Item $item", null, null, null, null)

    private val channels = listOf(1L, 2L, 3L).map { GuideChannel(it, it.toInt(), "Channel $it", null, isFavorite = false) }

    @Test
    fun `right steps cell by cell and pages once a cell starts near the edge`() {
        val grid = grid()
        grid.moveRight(strip, now)
        assertEquals(at(10, 20), grid.selectedTime)
        grid.moveRight(strip, now)
        assertEquals(at(11, 0), grid.selectedTime)
        assertEquals(at(10, 0), grid.targetStart)
        // 12:50 is within 15 minutes of the 12:00 edge: the hours page, keeping half an hour before it.
        grid.moveRight(strip, now)
        assertEquals(at(12, 50), grid.selectedTime)
        assertEquals(at(12, 0), grid.targetStart)
    }

    @Test
    fun `left steps back and lets focus leave at the guide's start`() {
        val grid = grid()
        grid.selectedTime = at(11, 0)
        assertTrue(grid.moveLeft(strip, now))
        assertEquals(at(10, 20), grid.selectedTime)
        assertTrue(grid.moveLeft(strip, now))
        // The program on now began before the guide's start: it is selected from the start of the hours.
        assertEquals(at(10, 0), grid.selectedTime)
        assertFalse(grid.moveLeft(strip, now))
    }

    @Test
    fun `left pages back to a long program's start`() {
        val grid = grid()
        grid.selectedTime = at(12, 50)
        grid.targetStart = at(12, 0)
        grid.moveLeft(strip, now)
        assertEquals(at(11, 0), grid.selectedTime)
        assertEquals(at(11, 0), grid.targetStart)
    }

    @Test
    fun `up and down keep the time and stop at the ends of the list`() {
        val grid = grid()
        grid.selectedTime = at(10, 40)
        assertTrue(grid.moveVertically(channels, 1))
        assertEquals(2, grid.selectedChannel)
        assertEquals(at(10, 40), grid.selectedTime)
        assertTrue(grid.moveVertically(channels, 1))
        assertFalse(grid.moveVertically(channels, 1))
        assertTrue(grid.moveVertically(channels, -2))
        assertFalse(grid.moveVertically(channels, -1))
        assertEquals(1, grid.selectedChannel)
    }

    @Test
    fun `dragging moves the hours but never before the current half hour`() {
        val grid = grid()
        assertTrue(grid.scrollByPx(300f, now))
        assertEquals(at(10, 30), grid.windowStart)
        assertEquals(grid.windowStart, grid.targetStart)
        assertFalse(grid.scrollByPx(-1200f, now))
        assertEquals(at(10, 0), grid.windowStart)
    }

    @Test
    fun `a tap selects the time under the finger`() {
        val grid = grid()
        grid.tap(3, x = 600f)
        assertEquals(3, grid.selectedChannel)
        assertEquals(at(11, 0), grid.selectedTime)
    }
}
