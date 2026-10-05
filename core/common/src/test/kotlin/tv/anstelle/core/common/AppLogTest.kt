package tv.anstelle.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLogTest {
    @Test
    fun ringBufferKeepsNewestEntriesInOrder() {
        val before = AppLog.lastSequence.value
        repeat(AppLog.CAPACITY + 10) { AppLog.i("test", "message $it") }

        val all = AppLog.snapshot()
        assertEquals(AppLog.CAPACITY, all.size)
        assertEquals("message ${AppLog.CAPACITY + 9}", all.last().message)
        assertTrue(all.zipWithNext().all { (a, b) -> b.sequence == a.sequence + 1 })
        assertTrue(all.first().sequence > before)
    }

    @Test
    fun snapshotAfterSequenceReturnsOnlyNewer() {
        AppLog.i("test", "a")
        val mark = AppLog.lastSequence.value
        AppLog.i("test", "b")
        AppLog.i("test", "c")
        assertEquals(listOf("b", "c"), AppLog.snapshot(afterSequence = mark).map { it.message })
    }
}
