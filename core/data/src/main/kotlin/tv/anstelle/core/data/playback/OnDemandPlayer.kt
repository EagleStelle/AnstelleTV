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
import tv.anstelle.core.data.ondemand.OnDemandRepository
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.player.CapabilityProbe
import tv.anstelle.core.player.JellyfinStreams
import tv.anstelle.core.player.PlayerFactory
import tv.anstelle.core.player.ProgramStream
import tv.anstelle.core.player.StreamDecision
import tv.anstelle.core.player.StreamInfo
import tv.anstelle.core.player.StreamPlanner
import javax.inject.Inject

enum class OnDemandStatus { LOADING, PLAYING, ENDED, ERROR }

data class OnDemandState(
    val status: OnDemandStatus = OnDemandStatus.LOADING,
    /** The show's title for an episode. */
    val title: String? = null,
    /** "S1E4 · Pilot", a movie's year, or null. */
    val detail: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val transcoded: Boolean = false,
    /** What plays after this episode, or null. */
    val next: String? = null,
    val message: String? = null,
)

/**
 * Plays a movie or an episode on demand, from any position, with pause and seeking (unlike
 * channels, which follow the schedule). An episode runs on into the rest of its show.
 *
 * It has its own player, so the channel pool keeps its state. Many TV devices can run only
 * one video decoder, so the caller stops channel playback first. One instance per screen;
 * main thread only.
 */
@OptIn(UnstableApi::class)
class OnDemandPlayer @Inject constructor(
    private val factory: PlayerFactory,
    private val probe: CapabilityProbe,
    private val streams: JellyfinStreams,
    private val repository: OnDemandRepository,
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    private val tokens: TokenStore,
    private val reporter: PlaybackReporter,
    private val tracks: TrackChoices,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(OnDemandState())
    val state: StateFlow<OnDemandState> = _state.asStateFlow()

    private var exo: Player? = null

    /** The player, created on first use. */
    val player: Player
        get() = exo ?: factory.create().player.also {
            it.addListener(listener)
            exo = it
        }

    private class Entry(val item: MediaItemEntity, val server: MediaServerEntity, val token: String, val stream: ProgramStream) {
        val mediaItem: MediaItem = MediaItem.Builder()
            .setUri(stream.url)
            .setMimeType(stream.mimeType)
            .setMediaId("demand@${item.id}")
            .build()
    }

    /** The player's playlist; the first entry is playing. */
    private val queue = ArrayDeque<Entry>()

    /** Episodes still to come after the queue. */
    private val upcoming = ArrayDeque<MediaItemEntity>()
    private var profileId = 0L
    private var session: ReportSession? = null
    private var retried = false
    private var tickJob: Job? = null

    /** Plays [itemId] from [positionMs]; an episode continues with the show's next ones. */
    fun play(itemId: Long, positionMs: Long, profileId: Long, maxRating: Int?) {
        this.profileId = profileId
        scope.launch {
            val item = itemDao.byId(itemId)
            val entry = item?.let { resolve(it, forceTranscode = false) }
            if (entry == null) {
                _state.update { it.copy(status = OnDemandStatus.ERROR, message = "This can't be played. Is the server signed in?") }
                return@launch
            }
            upcoming.clear()
            upcoming.addAll(repository.episodesAfter(entry.item, maxRating))
            queue.clear()
            queue.add(entry)
            val player = player
            tracks.applyDefaults(player, profileId)
            player.setMediaItems(listOf(entry.mediaItem), 0, positionMs.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = true
            retried = false
            show(entry)
            queueNext()
            startTicker()
        }
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

    /** Jumps to the next episode, when there is one. */
    fun skipToNext() {
        val player = exo ?: return
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    val positionMs: Long get() = exo?.currentPosition ?: 0
    val durationMs: Long get() = exo?.duration?.takeIf { it > 0 } ?: queue.firstOrNull()?.item?.durationMs ?: 0

    fun trackOptions(type: Int): List<TrackOption> = exo?.let { tracks.options(it, type) }.orEmpty()

    fun selectTrack(type: Int, option: TrackOption) {
        val player = exo ?: return
        scope.launch { tracks.select(player, profileId, type, option) }
    }

    /** Stops playback for good, reporting where it stopped. */
    fun release() {
        endSession(atEnd = false)
        tickJob?.cancel()
        exo?.let {
            it.removeListener(listener)
            it.release()
        }
        exo = null
        queue.clear()
        upcoming.clear()
        scope.cancel()
    }

    private suspend fun resolve(item: MediaItemEntity, forceTranscode: Boolean): Entry? {
        val server = serverDao.byId(item.serverId) ?: return null
        val token = withContext(io) { tokens.get(server.jellyfinServerId) } ?: return null
        val decision = if (forceTranscode) {
            StreamDecision.Transcode(listOf("direct play failed"))
        } else {
            StreamPlanner.decide(StreamInfo(item.container, item.videoCodec, item.audioCodec, item.width, item.height, item.videoBitDepth), withContext(io) { probe.capabilities })
        }
        if (decision is StreamDecision.Transcode) AppLog.i(TAG, "Transcoding item ${item.id}: ${decision.reasons.joinToString()}")
        val stream = streams.stream(server.baseUrl, token, item.remoteId, item.mediaSourceId, transcode = decision is StreamDecision.Transcode)
        return Entry(item, server, token, stream)
    }

    /** Adds the next episode to the playlist, so it follows without a gap. */
    private suspend fun queueNext() {
        val last = queue.lastOrNull() ?: return
        val following = upcoming.removeFirstOrNull()
        val entry = following?.let { resolve(it, forceTranscode = false) }
        if (queue.lastOrNull() !== last || exo == null) return
        if (entry != null) {
            player.addMediaItem(entry.mediaItem)
            queue.add(entry)
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
                transcoded = entry.stream.transcoded,
                next = queue.getOrNull(1)?.item?.let(::nextLabel),
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
            accessToken = entry.token,
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
        val player = exo ?: return
        val position = player.currentPosition
        val entry = resolve(failed.item, forceTranscode = true) ?: return fail("This can't be played.")
        queue[0] = entry
        player.setMediaItems(queue.map { it.mediaItem }, 0, position)
        player.prepare()
        player.playWhenReady = true
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
            startSession(entry, 0)
            show(entry)
            scope.launch { queueNext() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                endSession(atEnd = true)
                _state.update { it.copy(status = OnDemandStatus.ENDED, playing = false, buffering = false) }
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
