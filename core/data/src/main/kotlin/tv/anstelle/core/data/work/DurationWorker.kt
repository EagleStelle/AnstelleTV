package tv.anstelle.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.channels.ScheduleUpdater
import tv.anstelle.core.data.playback.MediaDurations
import tv.anstelle.core.data.playback.ChannelPlayer
import tv.anstelle.core.data.playback.TuneStatus
import tv.anstelle.core.database.dao.ScheduleDao
import java.util.concurrent.TimeUnit

/** Remote file maintenance has its own budget and never holds scheduling's write lock. */
@HiltWorker
class DurationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val durations: MediaDurations,
    private val schedules: ScheduleDao,
    private val updater: ScheduleUpdater,
    private val time: TimeSource,
    private val playback: ChannelPlayer,
    @Dispatcher(AnstelleDispatchers.Background) private val background: CoroutineDispatcher,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(background) {
        if (playback.state.value.let { it.status == TuneStatus.TUNING || it.buffering }) return@withContext Result.retry()
        val result = durations.refresh(onChanged = { changed ->
            changed.toList().chunked(500).forEach { schedules.invalidateForItems(it, time.nowUtcMs()) }
        })
        // Also repairs invalidations left by an interrupted earlier pass.
        updater.updateDue()
        if (result.hasMore) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(work: WorkManager) {
            val request = OneTimeWorkRequestBuilder<DurationWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            work.enqueueUniqueWork("duration-checks", ExistingWorkPolicy.KEEP, request)
        }
    }
}
