package tv.anstelle.core.data.guide

import kotlinx.coroutines.test.runTest
import tv.anstelle.core.data.stub
import tv.anstelle.core.database.dao.ScheduleDao
import kotlin.test.Test
import kotlin.test.assertEquals

class LongestSlotTest {
    private var longest = 3_600_000L
    private var reads = 0
    private var during: (() -> Unit)? = null

    private val dao = object : ScheduleDao by stub() {
        override suspend fun maxSlotMs(): Long {
            reads++
            val answer = longest
            during?.invoke()
            return answer
        }
    }
    private val slot = LongestSlot(dao)

    @Test
    fun `the table is read once until a schedule changes`() = runTest {
        assertEquals(3_600_000L, slot.ms())
        assertEquals(3_600_000L, slot.ms())
        assertEquals(1, reads)

        longest = 7_200_000L
        slot.invalidate()
        assertEquals(7_200_000L, slot.ms())
        assertEquals(7_200_000L, slot.ms())
        assertEquals(2, reads)
    }

    @Test
    fun `a read that a change overtakes is not kept`() = runTest {
        // The schedule changes while the table is read: the answer may predate it.
        during = {
            longest = 7_200_000L
            slot.invalidate()
        }
        assertEquals(3_600_000L, slot.ms())
        during = null
        assertEquals(7_200_000L, slot.ms())
        assertEquals(2, reads)
    }
}
