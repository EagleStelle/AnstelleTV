package tv.anstelle.core.data.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WatchAccount
import tv.anstelle.core.data.stats.DashboardHistory
import tv.anstelle.core.data.stats.FinishedView
import tv.anstelle.core.data.stats.PlayClock
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.jellyfin.Playstate
import tv.anstelle.core.jellyfin.PlaystateFactory
import tv.anstelle.core.jellyfin.PlaystateItem
import tv.anstelle.core.model.WatchSource
import tv.anstelle.core.model.WatchSync
import tv.anstelle.core.model.codeOf
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One item's playback, from its first frame to the moment it stops. */
class ReportSession internal constructor(
    internal val item: MediaItemEntity,
    internal val playstateItem: PlaystateItem,
    internal val server: MediaServerEntity,
    internal val account: WatchAccount,
    /** The channel it aired on, or null when it was played on demand. */
    internal val channelId: Long?,
    internal val profileId: Long,
) {
    internal val onDemand get() = channelId == null
    internal val coverage = WatchCoverage(item.durationMs)

    /** When it actually played, for the dashboard. */
    internal val clock = PlayClock()
    internal var sync = WatchSync.OFF
    internal var playstate: Playstate? = null

    /** The item's played mark before playback, when [WatchRules.needsPlayedBefore] says so. */
    internal var playedBefore: Boolean? = null
    internal var stopped = false
}

/**
 * Tells Jellyfin about playback after the first frame, in the background, so tuning never
 * waits for the server, as the user the profile plays as ([ProfileRepository.watchAccount]);
 * nothing goes to a user that keeps no history for the profile. Live TV and On demand each
 * have their own [WatchSync] setting: only
 * a setting that saves progress reports playback (start, progress every 10 s, stop);
 * watched marks are set or put back after it stops. Calls run one at a time in order, and a
 * failed one is logged and dropped. Every view is also handed to the profile's dashboard
 * ([DashboardHistory]) when it stops.
 */
@Singleton
class PlaybackReporter @Inject constructor(
    @ApplicationScope scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val playstates: PlaystateFactory,
    private val history: DashboardHistory,
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

    /**
     * [account] is who the item plays as (see [ProfileRepository.watchAccount]); [playing] says
     * whether the player is already playing, after which [playingChanged] follows its edges.
     */
    fun start(
        item: MediaItemEntity,
        server: MediaServerEntity,
        account: WatchAccount,
        playSessionId: String,
        transcoded: Boolean,
        channelId: Long?,
        profileId: Long,
        positionMs: Long,
        playing: Boolean,
    ): ReportSession {
        val session = ReportSession(
            item = item,
            playstateItem = PlaystateItem(item.remoteId, item.mediaSourceId, playSessionId, transcoded),
            server = server,
            account = account,
            channelId = channelId,
            profileId = profileId,
        )
        session.clock.set(playing, time.nowUtcMs())
        calls.trySend {
            if (!account.keepsHistory) return@trySend
            val sync = codeOf<WatchSync>(settings.get(if (session.onDemand) Settings.onDemandSync(profileId) else Settings.liveTvSync(profileId)))
            session.sync = sync
            if (sync == WatchSync.OFF) return@trySend
            val playstate = playstates.create(server.baseUrl, account.accessToken, account.userId, account.device).also { session.playstate = it }
            // Read before the start report, which can change it.
            if (WatchRules.needsPlayedBefore(sync, session.onDemand)) session.playedBefore = playstate.isPlayed(item.remoteId)
            if (sync.savesProgress) playstate.started(session.playstateItem, positionMs)
        }
        return session
    }

    /** The player started or stopped playing (a pause, buffering). Main thread. */
    fun playingChanged(session: ReportSession, playing: Boolean) {
        if (!session.stopped) session.clock.set(playing, time.nowUtcMs())
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
        val item = session.item
        val watched = if (session.onDemand) {
            WatchRules.reachedEnd(positionMs, item.durationMs)
        } else {
            WatchRules.watchedThrough(coveredMs, item.durationMs)
        }
        session.clock.stop(stoppedUtcMs)
        val view = FinishedView(
            profileId = session.profileId,
            source = if (session.onDemand) WatchSource.ON_DEMAND else WatchSource.CHANNEL,
            channelId = session.channelId,
            mediaItemId = item.id,
            title = item.title,
            showTitle = item.showTitle,
            mediaType = item.type,
            days = session.clock.byDay(ZoneId.systemDefault()),
            completed = watched,
        )
        calls.trySend { history.record(view) }
        calls.trySend {
            val playstate = session.playstate ?: return@trySend
            val sync = session.sync
            if (sync.savesProgress) playstate.stopped(session.playstateItem, positionMs)
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

    private fun describe(item: MediaItemEntity) = listOfNotNull(item.showTitle, item.title).joinToString(" · ")

    private companion object {
        const val TAG = "Reporting"
    }
}
