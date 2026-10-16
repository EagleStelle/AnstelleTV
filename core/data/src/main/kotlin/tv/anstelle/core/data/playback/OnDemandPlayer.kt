package tv.anstelle.core.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.data.Artwork
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WatchAccount
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.jellyfin.SegmentSource
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.model.MediaSegment
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.SegmentAction
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.codeOf
import tv.anstelle.core.model.playingAt
import tv.anstelle.core.player.CapabilityProbe
import tv.anstelle.core.player.FontCache
import tv.anstelle.core.player.JellyfinStreams
import tv.anstelle.core.player.PlayerFactory
import tv.anstelle.core.player.PooledPlayer
import tv.anstelle.core.player.ProgramStream
import tv.anstelle.core.player.StreamDecision
import tv.anstelle.core.player.StreamInfo
import tv.anstelle.core.player.StreamPlanner
import javax.inject.Inject

/** [RESTRICTED]: the profile may not watch the item ([OnDemandState.restriction] says why), so nothing plays. */
enum class OnDemandStatus { LOADING, PLAYING, ENDED, RESTRICTED, ERROR }

data class OnDemandState(
    val status: OnDemandStatus = OnDemandStatus.LOADING,
    /** The item playing, or 0 before one starts. */
    val itemId: Long = 0,
    /** When viewing this item began; retained across seeks, pauses and stream retries. */
    val startedAtUtcMs: Long = 0,
    /** The show's title for an episode. */
    val title: String? = null,
    /** "S1E4 - Pilot", a movie's year, or null. */
    val detail: String? = null,
    /** The show's or movie's title logo, shown in place of [title]; null when there is none. */
    val logoUrl: String? = null,
    /** True for an episode, false for a movie. */
    val isEpisode: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** The server, play method and picture and sound of what is playing. */
    val stream: StreamFacts? = null,
    /** What plays after this episode, or null. */
    val next: String? = null,
    /** The episode before this one in its show, or null (a movie, or the first episode). */
    val previous: String? = null,
    /** Whether Previous restarts this item; otherwise it moves to its predecessor when present. */
    val restartOnPrevious: Boolean = false,
    /** Whether Previous can restart this item or move to its predecessor. */
    val canRestartOrPrevious: Boolean = false,
    /** Why the profile may not watch the item; set with [OnDemandStatus.RESTRICTED]. */
    val restriction: Restriction? = null,
    val message: String? = null,
    /** The kind of segment playing that the profile is asked to skip ([SegmentAction.ASK_TO_SKIP]), or null. */
    val skip: SegmentType? = null,
)

/**
 * Plays a movie or an episode on demand, from any position, with pause and seeking (unlike
 * channels, which follow the schedule). An episode runs on into the rest of its show, and can
 * step back to the one before it.
 *
 * It has its own player, so the channel pool keeps its state. Many TV devices can run only
 * one video decoder, so the caller stops channel playback first. One instance per screen;
 * main thread only.
 *
 * Each episode starts on the profile's audio and subtitle defaults. An episode with ASS
 * subtitles isn't queued behind the one before it ([ProgramStream.chainsWith]); it starts
 * when that one ends.
 *
 * Each item's media segments are read from Jellyfin as it is resolved, without holding up
 * playback. While one plays, the profile's [SegmentAction] for its kind decides: a Skip key
 * ([OnDemandState.skip], [skipSegment]), a jump past it the first time it starts, or nothing.
 */
@OptIn(UnstableApi::class)
class OnDemandPlayer @Inject constructor(
    private val factory: PlayerFactory,
    private val probe: CapabilityProbe,
    private val streams: JellyfinStreams,
    private val repository: OnDemandRepository,
    private val profiles: ProfileRepository,
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    private val tokens: TokenStore,
    private val reporter: PlaybackReporter,
    private val tracks: TrackChoices,
    private val fonts: FontCache,
    private val segmentSource: SegmentSource,
    private val settings: SettingsRepository,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(OnDemandState())
    val state: StateFlow<OnDemandState> = _state.asStateFlow()

    private var pooled: PooledPlayer? = null
    private val exo: Player? get() = pooled?.player

    /** The player, created on first use. */
    val player: Player get() = pooledPlayer().player

    private fun pooledPlayer(): PooledPlayer = pooled ?: factory.create().also {
        it.player.addListener(listener)
        pooled = it
    }

    private class Entry(val item: MediaItemEntity, val server: MediaServerEntity, val account: WatchAccount, val stream: ProgramStream, val logoUrl: String?) {
        val mediaItem: MediaItem = stream.mediaItem("demand@${item.id}")
        val facts: StreamFacts = StreamFacts.of(item, server.name, stream.transcoded)

        /** Its media segments by start; empty until read, and for an item or server with none. */
        var segments: List<MediaSegment> = emptyList()

        /** The segments already jumped past on their own: seeking back into one plays it. */
        val skipped = HashSet<MediaSegment>()
    }

    /** The player's playlist; the first entry is playing. */
    private val queue = ArrayDeque<Entry>()

    /** Episodes still to come after the queue. */
    private val upcoming = ArrayDeque<MediaItemEntity>()

    /** Every episode of the show playing, in order; empty for a movie. */
    private var episodes: List<MediaItemEntity> = emptyList()

    /** The next episode when it couldn't be queued; it starts when the queue ends. */
    private var deferred: Entry? = null
    private var profileId = 0L
    private var prefs = TrackPrefs()
    private var segmentActions: Map<SegmentType, SegmentAction> = emptyMap()
    private var session: ReportSession? = null

    /**
     * Where the episode the player just moved on from was left, read as it moves on: by the
     * time its session ends, the player's own position is the next episode's.
     */
    private var leftAtMs: Long? = null
    private var retried = false
    private var nextJob: Job? = null
    private var tickJob: Job? = null
    private var segmentJob: Job? = null

    /**
     * Plays [itemId] from [positionMs] as the profile's Jellyfin user, when [access] lets the
     * profile watch it; an episode continues with the show's next ones.
     */
    fun play(itemId: Long, positionMs: Long, access: ProfileAccess) {
        profileId = access.profileId
        scope.launch {
            prefs = tracks.prefs(profileId)
            segmentActions = SegmentType.entries.associateWith { codeOf<SegmentAction>(settings.get(Settings.segmentAction(profileId, it))) }
            val item = itemDao.byId(itemId)
            val restriction = item?.let { Restriction.of(it.id, it.ratingScore, access) }
            if (restriction != null) {
                _state.update { it.copy(status = OnDemandStatus.RESTRICTED, restriction = restriction) }
                return@launch
            }
            val entry = item?.let { resolve(it, forceTranscode = false) }
            if (entry == null) {
                _state.update { it.copy(status = OnDemandStatus.ERROR, message = "This can't be played. Check the server's and the profile's sign-in.") }
                return@launch
            }
            episodes = repository.showEpisodes(entry.item, access)
            follow(entry.item)
            start(entry, positionMs)
            startTicker()
            watchSegments()
        }
    }

    /** Lines up the episodes after [item] to play once it ends. */
    private fun follow(item: MediaItemEntity) {
        upcoming.clear()
        val index = episodes.indexOfFirst { it.id == item.id }
        if (index >= 0) upcoming.addAll(episodes.subList(index + 1, episodes.size))
    }

    private fun episodeBefore(item: MediaItemEntity): MediaItemEntity? =
        episodes.indexOfFirst { it.id == item.id }.takeIf { it > 0 }?.let { episodes[it - 1] }

    /** Plays [entry] on its own from [positionMs], then queues what follows it. */
    private suspend fun start(entry: Entry, positionMs: Long) {
        val startedAtUtcMs = System.currentTimeMillis()
        deferred = null
        leftAtMs = null
        queue.clear()
        queue.add(entry)
        val pooled = pooledPlayer()
        load(pooled, positionMs)
        tracks.applyDefaults(pooled, prefs)
        pooled.player.playWhenReady = true
        retried = false
        show(entry, positionMs, startedAtUtcMs)
        queueNext()
    }

    /** Prepares the queue from [positionMs], handing libass the fonts of a side-loaded ASS subtitle. */
    private suspend fun load(pooled: PooledPlayer, positionMs: Long) {
        val fontFiles = fonts.load(queue.first().stream)
        pooled.player.setMediaItems(queue.map { it.mediaItem }, 0, positionMs.coerceAtLeast(0))
        pooled.subtitles.addFonts(fontFiles)
        pooled.player.prepare()
    }

    fun togglePlay() {
        val player = exo ?: return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0, 0)
            player.playWhenReady = true
        } else {
            player.playWhenReady = !player.playWhenReady
        }
    }

    fun seekBy(deltaMs: Long) {
        val player = exo ?: return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    fun seekTo(positionMs: Long) {
        exo?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Jumps past the segment the profile is asked to skip ([OnDemandState.skip]). */
    fun skipSegment() {
        val player = exo ?: return
        val segment = queue.firstOrNull()?.segments?.playingAt(player.currentPosition) ?: return
        skipPast(player, segment)
    }

    /**
     * Seeks to [segment]'s end. One that runs to the item's end ends it there, so the next
     * episode follows and the view counts as watched through, as in Jellyfin's apps.
     */
    private fun skipPast(player: Player, segment: MediaSegment) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(segment.endMs.coerceAtMost(duration))
        _state.update { it.copy(skip = null) }
    }

    /** Restarts this item unless it is already near its start, then goes to its predecessor. */
    fun restartOrPrevious() {
        val current = queue.firstOrNull()?.item ?: return
        val player = exo ?: return
        val previous = episodeBefore(current)
        if (shouldRestart(player.currentPosition, previous != null)) {
            val ended = player.playbackState == Player.STATE_ENDED
            player.seekTo(0)
            if (ended) player.playWhenReady = true
            refreshPreviousAction()
            return
        }
        if (previous == null) return
        endSession(atEnd = false)
        scope.launch {
            val entry = resolve(previous, forceTranscode = false) ?: return@launch fail("This can't be played.")
            follow(previous)
            start(entry, 0)
        }
    }

    /** Plays [itemId], another episode of the show playing, from [positionMs], then the ones after it. */
    fun playEpisode(itemId: Long, positionMs: Long) {
        val episode = episodes.firstOrNull { it.id == itemId } ?: return
        if (episode.id == queue.firstOrNull()?.item?.id) return
        endSession(atEnd = false)
        scope.launch {
            val entry = resolve(episode, forceTranscode = false) ?: return@launch fail("This can't be played.")
            follow(episode)
            start(entry, positionMs)
        }
    }

    /** Jumps to the next episode, when there is one. */
    fun skipToNext() {
        val player = exo ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            val next = deferred ?: return
            endSession(atEnd = false)
            scope.launch { start(next, 0) }
        }
    }

    val positionMs: Long get() = exo?.currentPosition ?: 0
    val durationMs: Long get() = exo?.duration?.takeIf { it > 0 } ?: queue.firstOrNull()?.item?.durationMs ?: 0

    fun trackOptions(type: Int): List<TrackOption> = exo?.let { tracks.options(it, type) }.orEmpty()

    fun selectTrack(type: Int, option: TrackOption) {
        exo?.let { tracks.select(it, type, option) }
    }

    /** Stops playback for good, reporting where it stopped. */
    fun release() {
        endSession(atEnd = false)
        tickJob?.cancel()
        segmentJob?.cancel()
        pooled?.let {
            it.player.removeListener(listener)
            it.release()
        }
        pooled = null
        queue.clear()
        upcoming.clear()
        episodes = emptyList()
        deferred = null
        scope.cancel()
    }

    /** [item], streaming as the profile's Jellyfin user; null when no one is signed in to play it as. */
    private suspend fun resolve(item: MediaItemEntity, forceTranscode: Boolean): Entry? {
        val server = serverDao.byId(item.serverId) ?: return null
        val token = withContext(io) { tokens.get(server.jellyfinServerId) } ?: return null
        val account = profiles.watchAccount(profileId, server, token) ?: return null
        val decision = if (forceTranscode) {
            StreamDecision.Transcode(listOf("direct play failed"))
        } else {
            StreamPlanner.decide(StreamInfo(item.container, item.videoCodec, item.audioCodec, item.width, item.height, item.videoBitDepth), withContext(io) { probe.capabilities })
        }
        if (decision is StreamDecision.Transcode) AppLog.i(TAG, "Transcoding item ${item.id}: ${decision.reasons.joinToString()}")
        val stream = streams.stream(
            server.baseUrl, account.accessToken, account.device, item.remoteId, item.mediaSourceId,
            transcode = decision is StreamDecision.Transcode, tracks = item.tracks, prefs = prefs,
        )
        val series = item.seriesRemoteId?.let { itemDao.byRemoteId(item.serverId, it) }
        return Entry(item, server, account, stream, Artwork.of(server.baseUrl, item, series).logoUrl).also(::loadSegments)
    }

    /** Reads [entry]'s segments in the background; until they arrive, nothing is offered to skip. */
    private fun loadSegments(entry: Entry) {
        scope.launch {
            entry.segments = try {
                withContext(io) { segmentSource.segments(entry.server.baseUrl, entry.account.accessToken, entry.item.remoteId, entry.account.device) }
            } catch (e: ServerConnectException) {
                // A server before 10.10 has no media segments at all.
                AppLog.i(TAG, "No media segments for item ${entry.item.id}: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Adds the next episode to the playlist, so it follows without a gap, or keeps it for when
     * this one ends when the two can't be chained.
     */
    private fun queueNext() {
        nextJob?.cancel()
        val last = queue.lastOrNull() ?: return
        nextJob = scope.launch {
            val following = upcoming.removeFirstOrNull()
            val entry = following?.let { resolve(it, forceTranscode = false) }
            if (queue.lastOrNull() !== last || exo == null) return@launch
            if (entry != null && last.stream.chainsWith(entry.stream)) {
                player.addMediaItem(entry.mediaItem)
                queue.add(entry)
            } else {
                deferred = entry
            }
            _state.update { it.copy(next = entry?.item?.let(::nextLabel)) }
        }
    }

    private fun show(entry: Entry, positionMs: Long, startedAtUtcMs: Long = _state.value.startedAtUtcMs) {
        val item = entry.item
        val previous = episodeBefore(item)
        val restart = shouldRestart(positionMs, previous != null)
        _state.update {
            it.copy(
                status = OnDemandStatus.PLAYING,
                itemId = item.id,
                startedAtUtcMs = startedAtUtcMs,
                title = programTitle(item.title, item.showTitle),
                detail = programDetail(item.title, item.showTitle, item.season, item.episode, item.year),
                logoUrl = entry.logoUrl,
                isEpisode = item.showTitle != null,
                stream = entry.facts,
                next = (queue.getOrNull(1) ?: deferred)?.item?.let(::nextLabel),
                previous = previous?.let(::nextLabel),
                restartOnPrevious = restart,
                canRestartOrPrevious = restart || previous != null,
                message = null,
                skip = null,
            )
        }
    }

    private fun nextLabel(item: MediaItemEntity) = programDetail(item.title, item.showTitle, item.season, item.episode, item.year) ?: item.title

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_MS)
                val player = exo ?: break
                session?.let { reporter.progress(it, player.currentPosition, paused = !player.isPlaying) }
            }
        }
    }

    /** Keeps Previous's label and enabled state aligned with current playback position. */
    private fun refreshPreviousAction() {
        val hasPrevious = _state.value.previous != null
        val restart = shouldRestart(exo?.currentPosition ?: 0, hasPrevious)
        val enabled = restart || hasPrevious
        if (restart != _state.value.restartOnPrevious || enabled != _state.value.canRestartOrPrevious) {
            _state.update { it.copy(restartOnPrevious = restart, canRestartOrPrevious = enabled) }
        }
    }

    private fun shouldRestart(positionMs: Long, hasPrevious: Boolean): Boolean =
        positionMs > PREVIOUS_RESTART_THRESHOLD_MS || !hasPrevious && positionMs > 0

    /**
     * Follows the segment playing a few times a second: offers to skip it, or jumps past it the
     * first time it starts while playing when the profile skips that kind on its own.
     */
    private fun watchSegments() {
        segmentJob?.cancel()
        segmentJob = scope.launch {
            while (isActive) {
                delay(SEGMENT_MS)
                val player = exo ?: break
                refreshPreviousAction()
                val active = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING
                val entry = queue.firstOrNull()
                val segment = if (active) entry?.segments?.playingAt(player.currentPosition) else null
                val action = segment?.let { segmentActions[it.type] }
                if (segment != null && action == SegmentAction.SKIP && player.playWhenReady && entry?.skipped?.add(segment) == true) {
                    skipPast(player, segment)
                    continue
                }
                val skip = segment?.type?.takeIf { action == SegmentAction.ASK_TO_SKIP }
                if (skip != _state.value.skip) _state.update { it.copy(skip = skip) }
            }
        }
    }

    private fun startSession(entry: Entry, positionMs: Long) {
        session = reporter.start(
            item = entry.item,
            server = entry.server,
            account = entry.account,
            playSessionId = entry.stream.playSessionId,
            transcoded = entry.stream.transcoded,
            channelId = null,
            source = tv.anstelle.core.model.WatchSource.ON_DEMAND,
            profileId = profileId,
            positionMs = positionMs,
            playing = exo?.isPlaying == true,
        )
    }

    /** [positionMs] is where it was left, when the player has already moved past it. */
    private fun endSession(atEnd: Boolean, entry: Entry? = queue.firstOrNull(), positionMs: Long? = null) {
        val ended = session ?: return
        session = null
        val position = positionMs ?: exo?.currentPosition ?: if (atEnd) entry?.item?.durationMs ?: 0 else 0
        reporter.stop(ended, position, ended = atEnd)
    }

    private suspend fun retryAsHls(failed: Entry) {
        val pooled = pooled ?: return
        val position = pooled.player.currentPosition
        val entry = resolve(failed.item, forceTranscode = true) ?: return fail("This can't be played.")
        queue[0] = entry
        if (queue.size > 1 && !entry.stream.chainsWith(queue[1].stream)) deferred = queue.removeLast()
        session?.let { reporter.restream(it, entry.stream.playSessionId, entry.stream.transcoded) }
        load(pooled, position)
        pooled.player.playWhenReady = true
        show(entry, position)
    }

    private fun fail(message: String) {
        endSession(atEnd = false)
        _state.update { it.copy(status = OnDemandStatus.ERROR, message = message) }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = exo ?: return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return
            if (player.currentMediaItemIndex == 0) return
            val finished = queue.removeFirstOrNull()
            // Next skips on from where it was (an outro skipped this way still counts past 90%).
            endSession(atEnd = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, entry = finished,
                positionMs = leftAtMs ?: finished?.item?.durationMs)
            leftAtMs = null
            player.removeMediaItems(0, player.currentMediaItemIndex)
            val entry = queue.firstOrNull() ?: return
            retried = false
            pooled?.let { tracks.applyDefaults(it, prefs) }
            startSession(entry, 0)
            show(entry, 0, System.currentTimeMillis())
            queueNext()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val finished = queue.firstOrNull() ?: return
                endSession(atEnd = true)
                scope.launch {
                    // Resolving the next stream may still be in flight when a short episode ends.
                    nextJob?.join()
                    val player = exo ?: return@launch
                    if (queue.firstOrNull() !== finished || player.playbackState != Player.STATE_ENDED) return@launch
                    val next = deferred
                    when {
                        player.hasNextMediaItem() -> {
                            player.seekToNextMediaItem()
                            player.playWhenReady = true
                        }
                        next != null -> start(next, 0)
                        else -> _state.update { it.copy(status = OnDemandStatus.ENDED, playing = false, buffering = false) }
                    }
                }
            } else {
                val buffering = playbackState == Player.STATE_BUFFERING
                if (_state.value.status == OnDemandStatus.ENDED && playbackState == Player.STATE_READY) {
                    _state.update { it.copy(status = OnDemandStatus.PLAYING) }
                }
                if (buffering != _state.value.buffering) _state.update { it.copy(buffering = buffering) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
            session?.let { reporter.playingChanged(it, isPlaying) }
        }

        // Like Jellyfin's own apps, a pause, a play or a seek is told at once, so the resume
        // point is current when the screen is left (leaving pauses) and the page reads it.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val player = exo ?: return
            session?.let { reporter.progress(it, player.currentPosition, paused = !playWhenReady) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                // Moving on to the next episode; called just before onMediaItemTransition.
                if (reason != Player.DISCONTINUITY_REASON_REMOVE) leftAtMs = oldPosition.positionMs
                return
            }
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            val player = exo ?: return
            session?.let { reporter.progress(it, newPosition.positionMs, paused = !player.playWhenReady) }
            refreshPreviousAction()
        }

        override fun onRenderedFirstFrame() {
            val entry = queue.firstOrNull() ?: return
            if (session == null) startSession(entry, exo?.currentPosition ?: 0)
        }

        override fun onPlayerError(error: PlaybackException) {
            val entry = queue.firstOrNull() ?: return
            AppLog.w(TAG, "On-demand playback failed: ${error.errorCodeName}", error)
            if (!entry.stream.transcoded && !retried && error.errorCode in FormatErrors) {
                retried = true
                // The session goes on over HLS: one view, one start in Jellyfin.
                scope.launch { retryAsHls(entry) }
            } else {
                fail("This can't be played right now (${error.errorCodeName}).")
            }
        }
    }

    private companion object {
        const val TAG = "OnDemand"
        const val PROGRESS_MS = 5_000L
        const val SEGMENT_MS = 250L
        /** A near-start Previous is the common "go back one" action. */
        const val PREVIOUS_RESTART_THRESHOLD_MS = 5_000L
    }
}
