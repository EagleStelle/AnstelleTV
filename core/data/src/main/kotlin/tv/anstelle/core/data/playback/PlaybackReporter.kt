package tv.anstelle.core.data.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.database.dao.WatchEventDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.WatchEventEntity
import tv.anstelle.core.jellyfin.Playstate
import tv.anstelle.core.jellyfin.PlaystateFactory
import tv.anstelle.core.jellyfin.PlaystateItem
import tv.anstelle.core.model.WatchSource
import tv.anstelle.core.model.WatchSync
import tv.anstelle.core.model.codeOf
import javax.inject.Inject
import javax.inject.Singleton

/** One item's playback, from its first frame to the moment it stops. */
class ReportSession internal constructor(
    internal val item: MediaItemEntity,
    internal val playstateItem: PlaystateItem,
    internal val server: MediaServerEntity,
    internal val accessToken: String,
    /** The channel it aired on, or null when it was played on demand. */
    internal val channelId: Long?,
    internal val profileId: Long,
    internal val startedUtcMs: Long,
) {
    internal val onDemand get() = channelId == null
    internal val coverage = WatchCoverage(item.durationMs)
    internal var sync = WatchSync.OFF
    internal var playstate: Playstate? = null

    /** The item's played mark before playback, when [WatchRules.needsPlayedBefore] says so. */
    internal var playedBefore: Boolean? = null
    internal var stopped = false
}

/**
 * Tells Jellyfin about playback after the first frame, in the background, so tuning never
 * waits for the server. Live TV and On demand each have their own [WatchSync] setting: only
 * a setting that saves progress reports playback (start, progress every 10 s, stop);
 * watched marks are set or put back after it stops. Calls run one at a time in order, and a
 * failed one is logged and dropped. Every view of 30 seconds or more is also kept as a local
 * watch event.
 */
@Singleton
class PlaybackReporter @Inject constructor(
    @ApplicationScope scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val playstates: PlaystateFactory,
    private val watchEventDao: WatchEventDao,
    private val time: TimeSource,
) {
    private val calls = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (call in calls) {
                try {
                    call()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w(TAG, "Playback report failed: ${e.message}")
                }
            }
        }
    }

    fun start(
        item: MediaItemEntity,
        server: MediaServerEntity,
        accessToken: String,
        playSessionId: String,
        transcoded: Boolean,
        channelId: Long?,
        profileId: Long,
        positionMs: Long,
    ): ReportSession {
        val session = ReportSession(
            item = item,
            playstateItem = PlaystateItem(item.remoteId, item.mediaSourceId, playSessionId, transcoded),
            server = server,
            accessToken = accessToken,
            channelId = channelId,
            profileId = profileId,
            startedUtcMs = time.nowUtcMs(),
        )
        calls.trySend {
            val sync = codeOf<WatchSync>(settings.get(if (session.onDemand) Settings.OnDemandSync else Settings.LiveTvSync))
            session.sync = sync
            val userId = server.userId
            if (sync == WatchSync.OFF || userId == null) return@trySend
            val playstate = playstates.create(server.baseUrl, accessToken, userId).also { session.playstate = it }
            // Read before the start report, which can change it.
            if (WatchRules.needsPlayedBefore(sync, session.onDemand)) session.playedBefore = playstate.isPlayed(item.remoteId)
            if (sync.savesProgress) playstate.started(session.playstateItem, positionMs)
        }
        return session
    }

    /** The player is playing at [positionMs]; called about once a second. Main thread. */
    fun playing(session: ReportSession, positionMs: Long) {
        if (!session.stopped) session.coverage.sample(positionMs)
    }

    fun progress(session: ReportSession, positionMs: Long, paused: Boolean) {
        if (session.stopped) return
        calls.trySend {
            if (session.sync.savesProgress) session.playstate?.progress(session.playstateItem, positionMs, paused)
        }
    }

    fun stop(session: ReportSession, positionMs: Long) {
        if (session.stopped) return
        session.stopped = true
        val stoppedUtcMs = time.nowUtcMs()
        val coveredMs = session.coverage.coveredMs
        calls.trySend {
            recordWatchEvent(session, stoppedUtcMs)
            val playstate = session.playstate ?: return@trySend
            val sync = session.sync
            val item = session.item
            if (sync.savesProgress) playstate.stopped(session.playstateItem, positionMs)
            val watched = if (session.onDemand) {
                WatchRules.reachedEnd(positionMs, item.durationMs)
            } else {
                WatchRules.watchedThrough(coveredMs, item.durationMs)
            }
            when (WatchRules.afterStop(sync, watched, session.onDemand)) {
                AfterStop.NOTHING -> Unit
                AfterStop.MARK_WATCHED -> if (!sync.savesProgress || !playstate.isPlayed(item.remoteId)) {
                    playstate.markWatched(item.remoteId, stoppedUtcMs)
                    val how = if (session.onDemand) "stopped at ${positionMs / 1000} s" else "${coveredMs / 1000} s played"
                    AppLog.i(TAG, "Marked ${describe(item)} watched: $how of ${item.durationMs / 1000} s")
                }
                AfterStop.RESTORE_PLAYED -> {
                    val before = session.playedBefore ?: return@trySend
                    if (playstate.isPlayed(item.remoteId) != before) {
                        playstate.setPlayed(item.remoteId, before)
                        AppLog.i(TAG, "Put back ${describe(item)} as ${if (before) "played" else "unplayed"}")
                    }
                }
            }
        }
    }

    private suspend fun recordWatchEvent(session: ReportSession, stoppedUtcMs: Long) {
        val watched = stoppedUtcMs - session.startedUtcMs
        if (watched < MIN_WATCH_EVENT_MS) return
        watchEventDao.insert(
            WatchEventEntity(
                profileId = session.profileId,
                source = if (session.onDemand) WatchSource.ON_DEMAND else WatchSource.CHANNEL,
                channelId = session.channelId,
                liveChannelId = null,
                mediaItemId = session.item.id,
                title = session.item.title,
                showTitle = session.item.showTitle,
                startUtcMs = session.startedUtcMs,
                durationMs = watched,
            ),
        )
    }

    private fun describe(item: MediaItemEntity) = listOfNotNull(item.showTitle, item.title).joinToString(" · ")

    private companion object {
        const val TAG = "Reporting"
        const val MIN_WATCH_EVENT_MS = 30_000L
    }
}
