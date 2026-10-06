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
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.WatchEventDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.WatchEventEntity
import tv.anstelle.core.jellyfin.Playstate
import tv.anstelle.core.jellyfin.PlaystateFactory
import tv.anstelle.core.jellyfin.PlaystateItem
import tv.anstelle.core.model.WatchReportMode
import tv.anstelle.core.model.WatchSource
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
    internal val joinedAtMs: Long,
    internal val startedUtcMs: Long,
) {
    internal var mode: WatchReportMode? = null
    internal var playstate: Playstate? = null
    internal var stopped = false
}

/**
 * Reports channel and on-demand playback to Jellyfin after the first frame, in the
 * background, so tuning never waits for the server. Calls run one at a time in order, and a
 * failed report is logged and dropped. Every view of 30 seconds or more is also kept as a
 * local watch event.
 */
@Singleton
class PlaybackReporter @Inject constructor(
    @ApplicationScope scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val playstates: PlaystateFactory,
    private val itemDao: MediaItemDao,
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
            joinedAtMs = positionMs,
            startedUtcMs = time.nowUtcMs(),
        )
        calls.trySend {
            val mode = codeOf<WatchReportMode>(settings.get(Settings.WatchReporting))
            session.mode = mode
            val userId = server.userId
            if (mode == WatchReportMode.OFF || userId == null) return@trySend
            val playstate = playstates.create(server.baseUrl, accessToken, userId).also { session.playstate = it }
            playstate.started(session.playstateItem, positionMs)
        }
        return session
    }

    fun progress(session: ReportSession, positionMs: Long, paused: Boolean) {
        if (session.stopped) return
        calls.trySend { session.playstate?.progress(session.playstateItem, positionMs, paused) }
    }

    fun stop(session: ReportSession, positionMs: Long) {
        if (session.stopped) return
        session.stopped = true
        val stoppedUtcMs = time.nowUtcMs()
        calls.trySend {
            recordWatchEvent(session, stoppedUtcMs)
            val playstate = session.playstate ?: return@trySend
            val mode = session.mode ?: return@trySend
            playstate.stopped(session.playstateItem, positionMs)
            val item = session.item
            val fullView = WatchRules.isFullView(session.joinedAtMs, positionMs, item.durationMs)
            when (WatchRules.afterStop(mode, item.playedOnServer, fullView, onDemand = session.channelId == null)) {
                AfterStop.NOTHING -> Unit
                AfterStop.UNMARK_IF_PLAYED -> if (playstate.isPlayed(item.remoteId)) {
                    playstate.markUnplayed(item.remoteId)
                    AppLog.i(TAG, "Unmarked ${describe(item)}: watched ${session.joinedAtMs / 1000}–${positionMs / 1000} s of ${item.durationMs / 1000} s")
                }
                AfterStop.RECORD_PLAYED -> if (playstate.isPlayed(item.remoteId)) itemDao.setPlayedOnServer(item.id, true)
            }
        }
    }

    private suspend fun recordWatchEvent(session: ReportSession, stoppedUtcMs: Long) {
        val watched = stoppedUtcMs - session.startedUtcMs
        if (watched < MIN_WATCH_EVENT_MS) return
        watchEventDao.insert(
            WatchEventEntity(
                profileId = session.profileId,
                source = if (session.channelId == null) WatchSource.ON_DEMAND else WatchSource.CHANNEL,
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
