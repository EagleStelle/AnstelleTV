package tv.anstelle.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import tv.anstelle.core.data.sync.LibrarySyncer
import tv.anstelle.core.data.sync.SyncOptions
import java.util.concurrent.TimeUnit

/** Runs library sync: periodically for every server, or on demand for one. */
@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: LibrarySyncer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val serverId = inputData.getLong(KEY_SERVER_ID, ALL_SERVERS)
        val options = SyncOptions(
            fullRefresh = inputData.getBoolean(KEY_FULL, false),
            forceReconcile = inputData.getBoolean(KEY_RECONCILE, false),
        )
        val ok = if (serverId == ALL_SERVERS) syncer.syncAll(options) else syncer.sync(serverId, options)
        // Even a partly failed sync may have changed items: channels and schedules follow.
        ScheduleWorker.runNow(WorkManager.getInstance(applicationContext))
        // Failures are stored on the server row and shown in settings; the next periodic run retries.
        return if (ok) Result.success() else Result.failure()
    }

    companion object {
        private const val PERIODIC_NAME = "library-sync"
        private const val ONE_TIME_TAG = "library-sync-now"
        private const val KEY_SERVER_ID = "serverId"
        private const val KEY_FULL = "full"
        private const val KEY_RECONCILE = "reconcile"
        private const val ALL_SERVERS = -1L
        private const val PERIOD_HOURS = 6L

        private val NetworkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(NetworkConstraint)
                .setInitialDelay(PERIOD_HOURS, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Syncs one server now (or every server when [serverId] is null). */
        fun syncNow(
            workManager: WorkManager,
            serverId: Long? = null,
            fullRefresh: Boolean = false,
            forceReconcile: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
                .setConstraints(NetworkConstraint)
                .addTag(ONE_TIME_TAG)
                .setInputData(
                    workDataOf(
                        KEY_SERVER_ID to (serverId ?: ALL_SERVERS),
                        KEY_FULL to fullRefresh,
                        KEY_RECONCILE to forceReconcile,
                    ),
                )
                .build()
            // Requests for the same server queue up behind each other instead of racing.
            workManager.enqueueUniqueWork(oneTimeName(serverId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(workManager: WorkManager, serverId: Long) {
            workManager.cancelUniqueWork(oneTimeName(serverId))
        }

        /** Stops every sync asked for and not finished; the periodic one stays. */
        fun cancelRequested(workManager: WorkManager) {
            workManager.cancelAllWorkByTag(ONE_TIME_TAG)
        }

        private fun oneTimeName(serverId: Long?) = "library-sync-${serverId ?: "all"}"
    }
}
