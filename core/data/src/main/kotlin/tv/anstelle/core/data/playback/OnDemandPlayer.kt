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
import tv.anstelle.core.data.WatchAccount
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.profiles.Restriction
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.TrackPrefs
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
    /** Why the profile may not watch the item; set with [OnDemandStatus.RESTRICTED]. */
    val restriction: Restriction? = null,
    val message: String? = null,
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
    private var session: ReportSession? = null
    private var retried = false
    private var tickJob: Job? = null

    /**
     * Plays [itemId] from [positionMs] as the profile's Jellyfin user, when [access] lets the
     * profile watch it; an episode continues with the show's next ones.
     */
    fun play(itemId: Long, positionMs: Long, access: ProfileAccess) {
        profileId = access.profileId
        scope.launch {
            prefs = tracks.prefs(profileId)
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
        deferred = null
        queue.clear()
        queue.add(entry)
        val pooled = pooledPlayer()
        load(pooled, positionMs)
        tracks.applyDefaults(pooled, prefs)
        pooled.player.playWhenReady = true
        retried = false
        show(entry)
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

    /** Goes back to the episode before this one, from its start, when there is one. */
    fun skipToPrevious() {
        val current = queue.firstOrNull()?.item ?: return
        val previous = episodeBefore(current) ?: return
        endSession(atEnd = false)
        scope.launch {
            val entry = resolve(previous, forceTranscode = false) ?: return@launch fail("This can't be played.")
            follow(previous)
            start(entry, 0)
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
        return Entry(item, server, account, stream, Artwork.of(server.baseUrl, item, series).logoUrl)
    }

    /**
     * Adds the next episode to the playlist, so it follows without a gap, or keeps it for when
     * this one ends when the two can't be chained.
     */
    private suspend fun queueNext() {
        val last = queue.lastOrNull() ?: return
        val following = upcoming.removeFirstOrNull()
        val entry = following?.let { resolve(it, forceTranscode = false) }
        if (queue.lastOrNull() !== last || exo == null) return
        if (entry != null && last.stream.chainsWith(entry.stream)) {
            player.addMediaItem(entry.mediaItem)
            queue.add(entry)
        } else {
            deferred = entry
        }
        _state.update { it.copy(next = entry?.item?.let(::nextLabel)) }
    }

    private fun show(entry: Entry) {
        val item = entry.item
        _state.update {
            it.copy(
                status = OnDemandStatus.PLAYING,
                title = programTitle(item.title, item.showTitle),
                detail = programDetail(item.title, item.showTitle, item.season, item.episode, item.year),
                logoUrl = entry.logoUrl,
                isEpisode = item.showTitle != null,
                stream = entry.facts,
                next = (queue.getOrNull(1) ?: deferred)?.item?.let(::nextLabel),
                previous = episodeBefore(item)?.let(::nextLabel),
                message = null,
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

    private fun startSession(entry: Entry, positionMs: Long) {
        session = reporter.start(
            item = entry.item,
            server = entry.server,
            account = entry.account,
            playSessionId = entry.stream.playSessionId,
            transcoded = entry.stream.transcoded,
            channelId = null,
            profileId = profileId,
            positionMs = positionMs,
        )
    }

    private fun endSession(atEnd: Boolean, entry: Entry? = queue.firstOrNull()) {
        val ended = session ?: return
        session = null
        val position = if (atEnd) entry?.item?.durationMs ?: 0 else exo?.currentPosition ?: 0
        reporter.stop(ended, position)
    }

    private suspend fun retryAsHls(failed: Entry) {
        val pooled = pooled ?: return
        val position = pooled.player.currentPosition
        val entry = resolve(failed.item, forceTranscode = true) ?: return fail("This can't be played.")
        queue[0] = entry
        if (queue.size > 1 && !entry.stream.chainsWith(queue[1].stream)) deferred = queue.removeLast()
        load(pooled, position)
        pooled.player.playWhenReady = true
        show(entry)
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
            endSession(atEnd = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, entry = finished)
            player.removeMediaItems(0, player.currentMediaItemIndex)
            val entry = queue.firstOrNull() ?: return
            retried = false
            pooled?.let { tracks.applyDefaults(it, prefs) }
            startSession(entry, 0)
            show(entry)
            scope.launch { queueNext() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                endSession(atEnd = true)
                val next = deferred
                if (next != null) {
                    scope.launch { start(next, 0) }
                } else {
                    _state.update { it.copy(status = OnDemandStatus.ENDED, playing = false, buffering = false) }
                }
            } else {
                val buffering = playbackState == Player.STATE_BUFFERING
                if (buffering != _state.value.buffering) _state.update { it.copy(buffering = buffering) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
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
                endSession(atEnd = false)
                scope.launch { retryAsHls(entry) }
            } else {
                fail("This can't be played right now (${error.errorCodeName}).")
            }
        }
    }

    private companion object {
        const val TAG = "OnDemand"
        const val PROGRESS_MS = 10_000L
    }
}
