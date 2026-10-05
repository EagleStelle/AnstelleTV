package tv.anstelle.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.database.dao.LiveTvDao
import tv.anstelle.core.database.dao.ScheduleDao
import java.util.concurrent.TimeUnit

/** Drops schedule slots and guide data that ended more than a day ago, keeping the database small. */
@HiltWorker
class ScheduleCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduleDao: ScheduleDao,
    private val liveTvDao: LiveTvDao,
    private val time: TimeSource,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cutoff = time.nowUtcMs() - KEEP_MS
        val slots = scheduleDao.deleteEndedBefore(cutoff)
        val programs = liveTvDao.deleteProgramsEndedBefore(cutoff)
        AppLog.i(TAG, "Removed $slots old schedule slots and $programs old guide entries")
        return Result.success()
    }

    companion object {
        private const val TAG = "ScheduleCleanup"
        private const val UNIQUE_NAME = "schedule-cleanup"
        private const val KEEP_MS = 24 * 60 * 60 * 1000L

        fun enqueue(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<ScheduleCleanupWorker>(1, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
