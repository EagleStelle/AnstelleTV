package tv.anstelle.core.data.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    playstateItem: PlaystateItem,
    internal val server: MediaServerEntity,
    internal val account: WatchAccount,
    /** The channel it aired on, or null when it was played on demand. */
    internal val channelId: Long?,
    internal val profileId: Long,
) {
    /** The stream as Jellyfin knows it; it changes when direct play falls back to HLS. */
    @Volatile internal var playstateItem: PlaystateItem = playstateItem
    internal val onDemand get() = channelId == null

    /** When it actually played, for the dashboard. */
    internal val clock = PlayClock()
    /** Eligibility uses monotonic time, so a device clock adjustment cannot qualify a hop. */
    internal val watchClock = PlayClock()
    internal var sync = WatchSync.OFF
    internal var playstate: Playstate? = null

    /** The item's played mark before playback, when [WatchRules.needsPlayedBefore] says so. */
    internal var playedBefore: Boolean? = null

    /** Jellyfin may hear of it (see [PlaybackReporter]); main thread. */
    internal var announced = false

    /** Jellyfin was sent its start, so progress and stop reports follow. */
    internal var startReported = false
    internal var stopped = false
}

/**
 * Tells Jellyfin about playback in the background, so tuning never waits for the server, as
 * the user the profile plays as ([ProfileRepository.watchAccount]); nothing goes to a user that
 * keeps no history for the profile. Live TV and On demand each have their own [WatchSync]
 * setting, read before anything goes to Jellyfin: Off sends nothing, Watched only sends only
 * the watched mark once a view counts. Progress writes only the resume fields, every 10 s and
 * at pauses, seeks and stop; Both uses Jellyfin's playback session reports.
 *
 * On demand Jellyfin hears from the first frame, as from its own apps: the viewer picked the
 * item. A channel only once [WatchRules.channelWatching] (surfing past one sends nothing),
 * including when it ends: a program shorter than 30 seconds never bypasses the rule.
 * Jellyfin clears an item's played mark at a start report; Live TV's Both mode restores it
 * at start and when an incomplete view stops. Calls run one at a time in order, a failed one logged and
 * dropped. Every view is also handed to the profile's dashboard ([DashboardHistory]) when it
 * stops, and announced on [reported] once Jellyfin has it.
 */
@Singleton
class PlaybackReporter internal constructor(
    scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val createPlaystate: (MediaServerEntity, WatchAccount) -> Playstate,
    private val history: DashboardHistory,
    private val time: TimeSource,
    private val elapsed: TimeSource = time,
) {
    @Inject constructor(
        @ApplicationScope scope: CoroutineScope,
        settings: SettingsRepository,
        playstates: PlaystateFactory,
        history: DashboardHistory,
        time: TimeSource,
    ) : this(scope, settings, { server, account ->
        playstates.create(server.baseUrl, account.accessToken, account.userId, account.device)
    }, history, time, TimeSource { System.nanoTime() / 1_000_000 })

    private val calls = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val _reported = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /**
     * The local id of an item whose playback has stopped and been told to Jellyfin, once those
     * calls are done, so lists showing what was watched can read it again.
     */
    val reported: SharedFlow<Long> = _reported.asSharedFlow()

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
     * [account] is who the item plays as (see [ProfileRepository.watchAccount]); [positionMs]
     * is where it starts, for on demand's start report; [playing] says whether the player is
     * already playing, after which [playingChanged] follows its edges.
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
        session.watchClock.set(playing, elapsed.nowUtcMs())
        if (session.onDemand) announce(session, positionMs, startReport = true)
        return session
    }

    /**
     * Lets Jellyfin hear of [session], as far as the account and the place's watch history
     * setting allow: the setting is read here, before anything is sent. [startReport] sends the
     * start for Both or the first resume write for Progress; without it, only marks follow
     * at the stop.
     */
    private fun announce(session: ReportSession, positionMs: Long, startReport: Boolean) {
        session.announced = true
        calls.trySend {
            val item = session.item
            val account = session.account
            if (!account.keepsHistory) {
                AppLog.i(TAG, "Not reporting ${describe(item)}: profile ${session.profileId} has no Jellyfin user of its own on ${session.server.name}")
                return@trySend
            }
            val key = if (session.onDemand) Settings.onDemandSync(session.profileId) else Settings.liveTvSync(session.profileId)
            val sync = codeOf<WatchSync>(settings.get(key))
            session.sync = sync
            if (sync == WatchSync.OFF) {
                AppLog.i(TAG, "Not reporting ${describe(item)}: ${if (session.onDemand) "On Demand" else "Live TV"} watch history is off")
                return@trySend
            }
            val playstate = createPlaystate(session.server, account).also { session.playstate = it }
            // Read before the start report, which can change it.
            if (WatchRules.needsPlayedBefore(sync, session.onDemand)) session.playedBefore = playstate.isPlayed(item.remoteId)
            if (sync == WatchSync.PROGRESS && startReport) {
                playstate.saveProgress(item.remoteId, positionMs, time.nowUtcMs())
            } else if (sync == WatchSync.BOTH && startReport) {
                playstate.started(session.playstateItem, positionMs)
                session.startReported = true
                // Jellyfin clears the mark at a start: put it back now, so a stop that never
                // arrives (the app killed) can't leave it cleared.
                if (session.playedBefore == true) playstate.setPlayed(item.remoteId, true)
            }
        }
    }

    /** The player started or stopped playing (a pause, buffering). Main thread. */
    fun playingChanged(session: ReportSession, playing: Boolean) {
        if (!session.stopped) {
            session.clock.set(playing, time.nowUtcMs())
            session.watchClock.set(playing, elapsed.nowUtcMs())
        }
    }

    /**
     * The player is playing at [positionMs]; called about once a second. A channel's view
     * reaches Jellyfin once it has played long enough to count as watching. Main thread.
     */
    fun playing(session: ReportSession, positionMs: Long) {
        if (session.stopped) return
        if (!session.announced && WatchRules.channelWatching(session.watchClock.playedMsAt(elapsed.nowUtcMs()))) {
            announce(session, positionMs, startReport = true)
        }
    }

    /** The same view goes on from another stream: direct play fell back to HLS. Main thread. */
    fun restream(session: ReportSession, playSessionId: String, transcoded: Boolean) {
        session.playstateItem = session.playstateItem.copy(playSessionId = playSessionId, transcoded = transcoded)
    }

    fun progress(session: ReportSession, positionMs: Long, paused: Boolean) {
        if (session.stopped || !session.announced) return
        val atUtcMs = time.nowUtcMs()
        calls.trySend {
            if (session.sync == WatchSync.PROGRESS) {
                session.playstate?.saveProgress(session.item.remoteId, positionMs, atUtcMs)
            } else if (session.sync == WatchSync.BOTH && session.startReported) {
                session.playstate?.progress(session.playstateItem, positionMs, paused)
            }
        }
    }

    fun stop(session: ReportSession, positionMs: Long, ended: Boolean = false) {
        if (session.stopped) return
        session.stopped = true
        val stoppedUtcMs = time.nowUtcMs()
        val item = session.item
        session.clock.stop(stoppedUtcMs)
        session.watchClock.stop(elapsed.nowUtcMs())
        val playedMs = session.watchClock.playedMs
        val watched = WatchRules.completed(positionMs, item.durationMs, ended, session.onDemand, playedMs)
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
        if (!session.announced) {
            // Exactly 30 seconds is required, even for a short program or a late join at its end.
            if (!session.onDemand && !WatchRules.channelWatching(playedMs)) return
            announce(session, positionMs, startReport = true)
        }
        calls.trySend {
            val playstate = session.playstate ?: return@trySend
            val sync = session.sync
            try {
                if (sync == WatchSync.PROGRESS) {
                    playstate.saveProgress(item.remoteId, positionMs, stoppedUtcMs)
                } else if (sync == WatchSync.BOTH && session.startReported) {
                    playstate.stopped(session.playstateItem, positionMs)
                    AppLog.i(TAG, "Saved ${describe(item)} at ${positionMs / 1000} s of ${item.durationMs / 1000} s")
                }
                when (WatchRules.afterStop(sync, watched, session.onDemand)) {
                    AfterStop.NOTHING -> Unit
                    // After a reported stop, Jellyfin may have marked it already.
                    AfterStop.MARK_WATCHED -> if (!session.startReported || !playstate.isPlayed(item.remoteId)) {
                        playstate.markWatched(item.remoteId, stoppedUtcMs)
                        val how = "stopped at ${positionMs / 1000} s after ${playedMs / 1000} s played"
                        AppLog.i(TAG, "Marked ${describe(item)} watched: $how of ${item.durationMs / 1000} s")
                    }
                    AfterStop.RESTORE_PLAYED -> {
                        val before = session.playedBefore
                        if (before != null && playstate.isPlayed(item.remoteId) != before) {
                            playstate.setPlayed(item.remoteId, before)
                            AppLog.i(TAG, "Put back ${describe(item)} as ${if (before) "played" else "unplayed"}")
                        }
                    }
                }
            } finally {
                _reported.tryEmit(item.id)
            }
        }
    }

    private fun describe(item: MediaItemEntity) = listOfNotNull(item.showTitle, item.title).joinToString(" · ")

    private companion object {
        const val TAG = "Reporting"
    }
}
