package tv.anstelle.core.data.guide

import tv.anstelle.core.database.dao.ScheduleDao
import java.util.concurrent.atomic.AtomicLong

/**
 * The longest slot of any schedule, which bounds the guide's and On Demand's range scans
 * ([ScheduleDao.guideWindow], [ScheduleDao.airingsAt]). Finding it reads the whole table, so
 * it is kept until a schedule changes ([invalidate], called on every `schedule_slot` write).
 */
class LongestSlot(private val scheduleDao: ScheduleDao) {
    /** Goes up with every change, so a read that raced one doesn't keep its stale answer. */
    private val version = AtomicLong()

    @Volatile
    private var cachedMs = UNKNOWN

    fun invalidate() {
        version.incrementAndGet()
        cachedMs = UNKNOWN
    }

    suspend fun ms(): Long {
        cachedMs.takeIf { it != UNKNOWN }?.let { return it }
        val seen = version.get()
        val longest = scheduleDao.maxSlotMs()
        if (version.get() == seen) cachedMs = longest
        return longest
    }

    private companion object {
        const val UNKNOWN = -1L
    }
}
