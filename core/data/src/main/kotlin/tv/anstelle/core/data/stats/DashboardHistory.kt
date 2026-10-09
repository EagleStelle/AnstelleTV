package tv.anstelle.core.data.stats

import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.database.dao.WatchEventDao
import tv.anstelle.core.database.entity.WatchEventEntity
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.WatchSource
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** A view that stopped, as the dashboard keeps it. */
class FinishedView(
    val profileId: Long,
    val source: WatchSource,
    val channelId: Long?,
    val mediaItemId: Long,
    val title: String,
    val showTitle: String?,
    val mediaType: MediaType,
    /** The time it played, by local day. */
    val days: List<DayShare>,
    /** It watched the item through (see `WatchRules`). */
    val completed: Boolean,
)

/**
 * What each profile watched, for its own dashboard. A view is kept once it stops, when it played
 * for [MIN_PLAYED_MS] or more and the profile has its dashboard on; it is one row per local day
 * it played on. Rows older than [KEEP_DAYS] are pruned.
 */
@Singleton
class DashboardHistory @Inject constructor(
    private val dao: WatchEventDao,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    fun observeEnabled(profileId: Long): Flow<Boolean> = settings.observe(Settings.dashboard(profileId))

    /** Keeps [view] unless it was too short or the profile has the dashboard off. */
    suspend fun record(view: FinishedView) {
        if (view.days.sumOf { it.playedMs } < MIN_PLAYED_MS) return
        if (!settings.get(Settings.dashboard(view.profileId))) return
        dao.insertAll(
            view.days.mapIndexed { i, day ->
                WatchEventEntity(
                    profileId = view.profileId,
                    source = view.source,
                    channelId = view.channelId,
                    liveChannelId = null,
                    mediaItemId = view.mediaItemId,
                    title = view.title,
                    showTitle = view.showTitle,
                    mediaType = view.mediaType,
                    startUtcMs = day.startUtcMs,
                    durationMs = day.playedMs,
                    localDay = day.localDay,
                    completed = view.completed && i == view.days.lastIndex,
                )
            },
        )
    }

    /** Stops keeping what the profile watches; what was kept stays unless [keepHistory] is false. */
    suspend fun turnOff(profileId: Long, keepHistory: Boolean) {
        settings.set(Settings.dashboard(profileId), false)
        if (!keepHistory) dao.deleteForProfile(profileId)
    }

    /** Keeps what the profile watches again; history kept before comes back with it. */
    suspend fun turnOn(profileId: Long) = settings.set(Settings.dashboard(profileId), true)

    /** Drops every profile's rows from more than [KEEP_DAYS] days ago. */
    suspend fun prune(zone: ZoneId = ZoneId.systemDefault()): Int {
        val removed = dao.deleteBefore(LocalDays.of(time.nowUtcMs(), zone) - KEEP_DAYS)
        if (removed > 0) AppLog.i(TAG, "Removed $removed dashboard rows older than $KEEP_DAYS days")
        return removed
    }

    companion object {
        private const val TAG = "Dashboard"
        const val MIN_PLAYED_MS = 30_000L
        const val KEEP_DAYS = 400L
    }
}
