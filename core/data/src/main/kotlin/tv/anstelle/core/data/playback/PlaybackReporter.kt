package tv.anstelle.core.data.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.*
import tv.anstelle.core.data.stats.DashboardHistory
import tv.anstelle.core.data.stats.FinishedView
import tv.anstelle.core.data.stats.PlayClock
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.jellyfin.*
import tv.anstelle.core.model.*
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One actual view; its source belongs to the player, never to the navigation route. */
class ReportSession internal constructor(
    internal val item: MediaItemEntity,
    @Volatile internal var playstateItem: PlaystateItem,
    internal val server: MediaServerEntity,
    internal var account: WatchAccount,
    internal val channelId: Long?,
    internal val profileId: Long,
    internal val source: WatchSource,
    internal val ordinal: Long,
) {
    internal val onDemand get() = source == WatchSource.ON_DEMAND
    internal val clock = PlayClock()
    internal val watchClock = PlayClock()
    internal var announced = false
    internal var stopped = false
    // The following state belongs to the reporting coroutine.
    internal var sync: WatchSync? = null
    internal var playstate: Playstate? = null
    internal var playedBefore: Boolean? = null
    internal var startAttempted = false
    internal var marked = false
    internal var qualified = false
    internal val presenceId = UUID.randomUUID().toString()
}

data class HistoryUpdate(val itemId: Long, val profileId: Long, val source: WatchSource)
data class HistoryFailure(val itemId: Long, val profileId: Long, val reconnect: Boolean)

/** Bounded, ordered background reporting. Only the newest pending position of each view is retained. */
@Singleton
class PlaybackReporter internal constructor(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val createPlaystate: (MediaServerEntity, WatchAccount) -> Playstate,
    private val history: DashboardHistory,
    private val time: TimeSource,
    private val elapsed: TimeSource = time,
    private val currentAccount: suspend (ReportSession) -> WatchAccount? = { it.account },
    private val signedOut: suspend (ReportSession) -> Unit = {},
    accountChanges: Flow<Unit> = emptyFlow(),
) {
    @Inject constructor(
        @ApplicationScope scope: CoroutineScope,
        settings: SettingsRepository,
        playstates: PlaystateFactory,
        history: DashboardHistory,
        time: TimeSource,
        profiles: ProfileRepository,
    ) : this(scope, settings, { server, account ->
        playstates.create(server.baseUrl, account.accessToken, account.userId, account.device)
    }, history, time, TimeSource { System.nanoTime() / 1_000_000 },
        { profiles.historyAccount(it.profileId, it.server, it.account.userId) },
        { profiles.reportingSignedOut(it.profileId, it.server, it.account) }, profiles.accountChanges)

    private data class Update(val positionMs: Long, val paused: Boolean, val atUtcMs: Long, val watched: Boolean, val ended: Boolean = false)
    private val guard = Any()
    private val pending = LinkedHashMap<ReportSession, Update>()
    private val waiting = LinkedHashMap<ReportSession, Update>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var nextOrdinal = 0L
    private val presenceOwners = HashMap<Triple<Long, String, String?>, Long>()
    private val writtenItems = LinkedHashMap<Triple<Long, Long, String>, Long>()
    private val _reported = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    /** Completed writes only. Existing consumers may refresh visible history after a stop. */
    val reported = _reported.asSharedFlow()
    private val _updates = MutableSharedFlow<HistoryUpdate>(extraBufferCapacity = 16)
    val updates = _updates.asSharedFlow()
    private val _failures = MutableSharedFlow<HistoryFailure>(extraBufferCapacity = 16)
    val failures = _failures.asSharedFlow()

    init {
        scope.launch {
            accountChanges.collect {
                synchronized(guard) {
                    for ((session, update) in waiting) pending.putIfAbsent(session, update)
                    waiting.clear()
                    trim(pending)
                }
                wake.trySend(Unit)
            }
        }
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val entry = synchronized(guard) {
                        pending.entries.firstOrNull()?.let {
                            waiting.remove(it.key)
                            it.key to pending.remove(it.key)!!
                        }
                    } ?: break
                    val (session, update) = entry
                    try {
                        report(session, update)
                    } catch (e: Exception) {
                        if (e is CancellationException && e !is TimeoutCancellationException) throw e
                        val reconnect = (e as? ServerConnectException)?.problem == ServerProblem.SIGNED_OUT
                        synchronized(guard) {
                            if (session !in pending) waiting[session] = update
                            trim(waiting)
                        }
                        if (reconnect) signedOut(session)
                        val fresh = if (reconnect) currentAccount(session) else null
                        if (fresh != null && fresh.accessToken != session.account.accessToken) submit(session, update)
                        _failures.tryEmit(HistoryFailure(session.item.id, session.profileId, reconnect))
                        AppLog.w(TAG, "History update failed${if (reconnect) "; reconnect profile" else ""}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun trim(queue: LinkedHashMap<ReportSession, Update>) {
        while (queue.size > 64) queue.remove(queue.keys.first())
    }

    private fun submit(session: ReportSession, update: Update) {
        synchronized(guard) {
            // Positions coalesce; a qualifying completion must survive a later seek.
            session.qualified = session.qualified || update.watched
            waiting.remove(session)
            pending[session] = update.copy(watched = session.qualified)
            trim(pending)
        }
        wake.trySend(Unit)
    }

    fun start(
        item: MediaItemEntity, server: MediaServerEntity, account: WatchAccount,
        playSessionId: String, transcoded: Boolean, channelId: Long?, profileId: Long,
        positionMs: Long, playing: Boolean,
        source: WatchSource,
    ): ReportSession {
        val session = ReportSession(item, PlaystateItem(item.remoteId, item.mediaSourceId, playSessionId, transcoded),
            server, account, channelId, profileId, source, ++nextOrdinal)
        session.clock.set(playing, time.nowUtcMs())
        session.watchClock.set(playing, elapsed.nowUtcMs())
        if (session.onDemand) {
            session.announced = true
            progress(session, positionMs, !playing)
        }
        return session
    }

    fun playingChanged(session: ReportSession, playing: Boolean) {
        if (session.stopped) return
        session.clock.set(playing, time.nowUtcMs())
        session.watchClock.set(playing, elapsed.nowUtcMs())
    }

    fun playing(session: ReportSession, positionMs: Long) {
        if (session.stopped) return
        if (!session.announced && WatchRules.channelWatching(session.watchClock.playedMsAt(elapsed.nowUtcMs()))) {
            session.announced = true
            progress(session, positionMs, paused = false)
        }
    }

    fun restream(session: ReportSession, playSessionId: String, transcoded: Boolean) {
        session.playstateItem = session.playstateItem.copy(playSessionId = playSessionId, transcoded = transcoded)
    }

    fun progress(session: ReportSession, positionMs: Long, paused: Boolean) {
        if (session.stopped || !session.announced) return
        val watched = WatchRules.completed(positionMs, session.item.durationMs, false, session.onDemand,
            session.watchClock.playedMsAt(elapsed.nowUtcMs()))
        submit(session, Update(positionMs.coerceAtLeast(0), paused, time.nowUtcMs(), watched))
    }

    fun stop(session: ReportSession, positionMs: Long, ended: Boolean = false) {
        if (session.stopped) return
        session.stopped = true
        val now = time.nowUtcMs()
        session.clock.stop(now)
        session.watchClock.stop(elapsed.nowUtcMs())
        val watched = WatchRules.completed(positionMs, session.item.durationMs, ended, session.onDemand, session.watchClock.playedMs)
        val view = FinishedView(session.profileId, session.source, session.channelId, session.item.id,
            session.item.title, session.item.showTitle, session.item.type, session.clock.byDay(ZoneId.systemDefault()), watched)
        scope.launch {
            try { history.record(view) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.w(TAG, "Dashboard history failed: ${e.message}") }
        }
        if (!session.onDemand && !WatchRules.channelWatching(session.watchClock.playedMs)) return
        session.announced = true
        submit(session, Update(positionMs.coerceAtLeast(0), true, now, watched, ended = true))
    }

    private suspend fun report(session: ReportSession, update: Update) {
        if (!session.account.keepsHistory) return
        val mode = session.sync ?: codeOf<WatchSync>(settings.get(
            if (session.onDemand) Settings.onDemandSync(session.profileId) else Settings.liveTvSync(session.profileId)
        )).also { session.sync = it }
        if (mode == WatchSync.OFF) return
        val itemKey = Triple(session.profileId, session.server.id, session.item.remoteId)
        // Reconnecting must not replay an older resume point over a newer view of the item.
        val superseded = (writtenItems[itemKey] ?: 0) > session.ordinal
        if (superseded && (!mode.marksWatched || !update.watched || session.marked)) return
        val account = currentAccount(session) ?: throw ServerConnectException(ServerProblem.SIGNED_OUT, "Profile sign-in required")
        check(account.userId == session.account.userId) { "History user changed" }
        if (session.account != account) {
            session.account = account
            session.playstate = null
            session.startAttempted = false
        }
        val api = session.playstate ?: createPlaystate(session.server, account).also { session.playstate = it }
        val item = session.item
        if (superseded) {
            // A valid earlier completion still counts, but its old position and stop
            // must never replace the newer player's resume point or Now Playing entry.
            request { api.setPlayed(item.remoteId, true) }
            session.marked = true
            _updates.tryEmit(HistoryUpdate(item.id, session.profileId, session.source))
            _reported.tryEmit(item.id)
            return
        }
        val presenceKey = Triple(session.server.id, account.userId, account.device)
        val obsoletePresence = (presenceOwners[presenceKey] ?: 0) > session.ordinal
        val stream = session.playstateItem.let {
            if (mode == WatchSync.PROGRESS) it.copy(presence = PlaybackPresence(session.presenceId, item.title, item.showTitle, item.durationMs)) else it
        }
        if (mode.savesProgress && !session.startAttempted && !obsoletePresence) {
            if (WatchRules.needsPlayedBefore(mode, session.onDemand) && session.playedBefore == null) {
                session.playedBefore = request { api.isPlayed(item.remoteId) }
            }
            // A start can increment PlayCount: never blindly retry an ambiguous response.
            session.startAttempted = true
            try { withTimeout(10_000) { api.started(stream, update.positionMs) } }
            catch (e: Exception) {
                if ((e as? ServerConnectException)?.problem == ServerProblem.SIGNED_OUT) session.startAttempted = false
                throw e
            }
            presenceOwners[presenceKey] = session.ordinal
            if (session.playedBefore == true) request { api.setPlayed(item.remoteId, true) }
        }
        if (mode == WatchSync.PROGRESS) {
            request { api.saveProgress(item.remoteId, update.positionMs, update.atUtcMs) }
            if (!obsoletePresence) {
                request { if (update.ended) api.stopped(stream, update.positionMs) else api.progress(stream, update.positionMs, update.paused) }
                presenceOwners[presenceKey] = session.ordinal
            }
        } else if (mode == WatchSync.BOTH) {
            if (obsoletePresence) {
                // A late stop would clear the current session on Jellyfin. Save the old
                // item's final history directly while leaving the newer player alone.
                request { api.saveProgress(item.remoteId, update.positionMs, update.atUtcMs) }
            } else {
                request { if (update.ended) api.stopped(stream, update.positionMs) else api.progress(stream, update.positionMs, update.paused) }
                presenceOwners[presenceKey] = session.ordinal
            }
        }
        val markedNow = mode.marksWatched && update.watched && !session.marked
        if (markedNow) {
            // Status must leave existing resume data and play counts alone.
            request { api.setPlayed(item.remoteId, true) }
            session.marked = true
        } else if (update.ended && mode == WatchSync.BOTH && !session.onDemand && !update.watched && !session.marked) {
            session.playedBefore?.let { before -> request { api.setPlayed(item.remoteId, before) } }
        }
        if (mode.savesProgress || markedNow) {
            writtenItems[itemKey] = session.ordinal
            while (writtenItems.size > 64) writtenItems.remove(writtenItems.keys.first())
            _updates.tryEmit(HistoryUpdate(item.id, session.profileId, session.source))
        }
        if ((mode.savesProgress && update.ended) || markedNow) _reported.tryEmit(item.id)
    }

    private suspend fun <T> request(block: suspend () -> T): T {
        repeat(3) { attempt ->
            try { return withTimeout(10_000) { block() } }
            catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val retry = e is IOException || e is TimeoutCancellationException ||
                    (e is ServerConnectException && e.problem in setOf(ServerProblem.UNREACHABLE, ServerProblem.SERVER_ERROR))
                if (!retry || attempt == 2) throw e
                delay(if (attempt == 0) 250 else 1_000)
            }
        }
        error("Unreachable")
    }

    private companion object { const val TAG = "Reporting" }
}
