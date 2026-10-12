package tv.anstelle.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ScheduleUpdater
import java.util.concurrent.TimeUnit

/**
 * Brings the channels made from the library in line with it, then keeps every channel's
 * schedule 30 days ahead and regenerates the ones whose inputs changed. Runs after each
 * sync, at app start and twice a day.
 */
@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val channels: ChannelRepository,
    private val updater: ScheduleUpdater,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        // Logos stored before the current standard are made again once; afterwards this only reads the channels.
        channels.standardizeLogos()
        channels.syncGenerated()
        updater.updateAll()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e(TAG, "Schedule update failed", e)
        Result.failure()
    }

    companion object {
        private const val TAG = "ScheduleWorker"
        private const val PERIODIC_NAME = "schedule-extend"
        private const val ONE_TIME_NAME = "schedule-update"
        private const val PERIOD_HOURS = 12L

        fun schedulePeriodic(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<ScheduleWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setInitialDelay(PERIOD_HOURS, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Runs once now, after any run already queued. */
        fun runNow(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<ScheduleWorker>().build()
            workManager.enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
