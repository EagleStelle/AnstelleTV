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
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ScheduleUpdater
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Brings the channels made from the library in line with it, then keeps every channel's
 * schedule [ScheduleUpdater.HORIZON_MS] ahead and regenerates the ones whose inputs changed.
 * Runs in full after each sync. At app start and twice a day it only tops up the schedules
 * that are due ([ScheduleUpdater.updateDue]), since every other change updates its own
 * schedules; a new app version or time zone still gets the full run. Runs on low-priority
 * threads so the screen and playback keep their CPU.
 */
@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val channels: ChannelRepository,
    private val updater: ScheduleUpdater,
    private val settings: SettingsRepository,
    @Dispatcher(AnstelleDispatchers.Background) private val background: CoroutineDispatcher,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        withContext(background) {
            // What the last full run was made for: the app as installed, in this time zone.
            val made = "${installed()}|${ZoneId.systemDefault().id}"
            if (!inputData.getBoolean(KEY_FULL, true) && settings.get(Settings.SchedulesMadeFor) == made) {
                updater.updateDue()
            } else {
                // Logos stored before the current standard are made again once; afterwards this only reads the channels.
                channels.standardizeLogos()
                // One read of the library serves the channels and their schedules.
                val catalog = updater.catalog()
                channels.syncGenerated(catalog)
                updater.updateAll(catalog = catalog)
                settings.set(Settings.SchedulesMadeFor, made)
            }
        }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e(TAG, "Schedule update failed", e)
        Result.failure()
    }

    private fun installed(): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    companion object {
        private const val TAG = "ScheduleWorker"
        private const val PERIODIC_NAME = "schedule-extend"
        private const val ONE_TIME_NAME = "schedule-update"
        private const val PERIOD_HOURS = 12L
        private const val KEY_FULL = "full"

        fun schedulePeriodic(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<ScheduleWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setInitialDelay(PERIOD_HOURS, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_FULL to false))
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Runs once now, after any run already queued. [full] reads the whole library again, as
         * after a sync; otherwise only the schedules that are due are topped up.
         */
        fun runNow(workManager: WorkManager, full: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<ScheduleWorker>().setInputData(workDataOf(KEY_FULL to full)).build()
            workManager.enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
