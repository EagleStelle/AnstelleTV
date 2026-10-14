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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.stats.DashboardHistory
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.dao.LiveTvDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.player.FontCache
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping that keeps the app's storage small: drops schedule slots and guide data that
 * ended more than a day ago and dashboard history past [DashboardHistory.KEEP_DAYS], trims the
 * subtitle font cache, and gives the database file back its free pages once a good part of it is
 * free (SQLite reuses freed pages but never shrinks the file by itself).
 */
@HiltWorker
class ScheduleCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduleDao: ScheduleDao,
    private val liveTvDao: LiveTvDao,
    private val history: DashboardHistory,
    private val fonts: FontCache,
    private val database: AnstelleDatabase,
    private val time: TimeSource,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cutoff = time.nowUtcMs() - KEEP_MS
        val slots = scheduleDao.deleteEndedBefore(cutoff)
        val programs = liveTvDao.deleteProgramsEndedBefore(cutoff)
        AppLog.i(TAG, "Removed $slots old schedule slots and $programs old guide entries")
        history.prune()
        fonts.tidy()
        withContext(io) { compact() }
        return Result.success()
    }

    /** Rewrites the database file without its free pages once a quarter of it is free. */
    private fun compact() {
        val db = database.openHelper.writableDatabase
        fun pragma(name: String) = db.query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val pages = pragma("page_count")
        val free = pragma("freelist_count")
        if (free < MIN_FREE_PAGES || free * 4 < pages) return
        val started = System.nanoTime()
        db.execSQL("VACUUM")
        AppLog.i(TAG, "Compacted the database: $free of $pages pages were free (${(System.nanoTime() - started) / 1_000_000} ms)")
    }

    companion object {
        private const val TAG = "ScheduleCleanup"
        private const val UNIQUE_NAME = "schedule-cleanup"
        private const val KEEP_MS = 24 * 60 * 60 * 1000L

        /** About 1 MB of 4 KB pages: less isn't worth rewriting the file for. */
        private const val MIN_FREE_PAGES = 256L

        fun enqueue(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<ScheduleCleanupWorker>(1, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
