package tv.anstelle.core.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ScheduleUpdater
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.programTitle
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.player.CapabilityProbe
import tv.anstelle.core.player.JellyfinStreams
import tv.anstelle.core.player.PlayerPool
import tv.anstelle.core.player.ProgramStream
import tv.anstelle.core.player.StreamDecision
import tv.anstelle.core.player.StreamInfo
import tv.anstelle.core.player.StreamPlanner
import tv.anstelle.core.player.TuneTrace
import javax.inject.Inject
import javax.inject.Singleton

/** A program as the info banner shows it. */
data class ProgramInfo(
    /** The show (or movie) title. */
    val title: String,
    /** "S1E4 · Pilot", a movie's year, or null. */
    val detail: String?,
    val startUtcMs: Long,
    val endUtcMs: Long,
)

enum class TuneStatus { IDLE, TUNING, PLAYING, OFF_AIR, NO_SCHEDULE, ERROR }

data class PlaybackState(
    val channel: Channel? = null,
    val status: TuneStatus = TuneStatus.IDLE,
    val buffering: Boolean = false,
    val now: ProgramInfo? = null,
    val next: ProgramInfo? = null,
    val transcoded: Boolean = false,
    val message: String? = null,
    /** Goes up with every tune, so the screen knows to show the info banner. */
    val tuneCount: Int = 0,
)

/** One scheduled program, ready to play. */
private class Program(
    val slot: ScheduleSlotEntity,
    val item: MediaItemEntity,
    val server: MediaServerEntity,
    val token: String,
    val stream: ProgramStream,
) {
    val mediaItem: MediaItem = MediaItem.Builder()
        .setUri(stream.url)
        .setMimeType(stream.mimeType)
        .setMediaId("${slot.channelId}@${slot.startUtcMs}")
        .build()

    val info: ProgramInfo = programInfo(item, slot.startUtcMs, slot.endUtcMs)
}

/**
 * Plays channels: what a channel airs comes from SQLite and the stream URL is built locally,
 * so tuning makes no server call.
 *
 * - **Back to back:** the player's playlist holds the program on now and the next one, so
 *   one runs into the other without a gap. At each transition the following program is
 *   queued, and when the new program is more than [MAX_DRIFT_MS] behind the wall clock (after
 *   buffering, say) it seeks ahead.
 * - **Pool:** the channel in the surfing direction (or the one focused in the guide) is
 *   pre-warmed on the standby player, and tuning to it swaps the players.
 * - **Off air** shows until the off-air stretch ends, then the channel tunes itself again.
 * - **Fallback:** a file the device turns out not to play is retried once through Jellyfin's
 *   HLS transcoding.
 * - **Screens:** the full-screen player and the guide's preview window share the playback.
 *   Each [acquire]s it while visible; it stops when the last one [release]s it, so moving
 *   between the two keeps the channel playing.
 *
 * Main thread only.
 */
@Singleton
@OptIn(UnstableApi::class)
class ChannelPlayer @Inject constructor(
    private val pool: PlayerPool,
    private val probe: CapabilityProbe,
    private val streams: JellyfinStreams,
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    private val tokens: TokenStore,
    private val updater: ScheduleUpdater,
    private val reporter: PlaybackReporter,
    private val tracks: TrackChoices,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)

    /** The player on screen, or null while nothing plays (off air, say). */
    val player: StateFlow<Player?> = _player.asStateFlow()

    private var lineup: List<Channel> = emptyList()
    private var current: Channel? = null
    private var previous: Channel? = null
    private var direction = 1
    private var holders = 0

    /** The channel to pre-warm instead of the next one in the surfing direction. */
    private var standbyTarget: Long? = null

    /** The on-screen player's playlist; the first entry is playing. */
    private val queue = ArrayDeque<Program>()
    private var standbyQueue: List<Program> = emptyList()
    private var session: ReportSession? = null
    private var trace: TuneTrace? = null
    private var retriedSlot = Long.MIN_VALUE

    private var tuneJob: Job? = null
    private var waitJob: Job? = null
    private var tickJob: Job? = null
    private var handoverJob: Job? = null

    /** The channels surfing goes through, in number order. */
    fun setLineup(channels: List<Channel>) {
        lineup = channels.sortedBy { it.number }
    }

    /** A screen showing the playback became visible. */
    fun acquire() {
        holders++
        handoverJob?.cancel()
        handoverJob = null
    }

    /**
     * A screen showing the playback went away. When it was the last one, playback stops a
     * moment later, unless another screen takes it over first: leaving the full-screen player
     * may release it before the guide below acquires it.
     */
    fun release() {
        if (holders == 0) return
        holders--
        if (holders > 0) return
        handoverJob?.cancel()
        handoverJob = scope.launch {
            delay(HANDOVER_MS)
            stop()
        }
    }

    /** Tunes to [channelId], unless it is the channel already on. */
    fun tune(channelId: Long) {
        if (current?.id == channelId && _state.value.status != TuneStatus.IDLE) return
        lineup.firstOrNull { it.id == channelId }?.let { tuneTo(it, surf = 0) }
    }

    /** Pre-warms [channelId] on the standby player, e.g. while the guide has it focused. */
    fun prewarm(channelId: Long) {
        if (channelId == current?.id) return
        standbyTarget = channelId
        if (_player.value == null) return
        lineup.firstOrNull { it.id == channelId }?.let { scope.launch { prewarm(it) } }
    }

    /** Tunes to channel [number]; false when there is no such channel. */
    fun tuneNumber(number: Int): Boolean {
        val channel = lineup.firstOrNull { it.number == number } ?: return false
        tuneTo(channel, surf = 0)
        return true
    }

    fun channelUp() = neighbor(1)?.let { tuneTo(it, surf = 1) }

    fun channelDown() = neighbor(-1)?.let { tuneTo(it, surf = -1) }

    fun lastChannel() = previous?.let { tuneTo(it, surf = 0) }

    fun trackOptions(type: Int): List<TrackOption> = _player.value?.let { tracks.options(it, type) }.orEmpty()

    fun selectTrack(type: Int, option: TrackOption) {
        val player = _player.value ?: return
        val profileId = current?.profileId ?: return
        scope.launch { tracks.select(player, profileId, type, option) }
    }

    /** Stops playback, e.g. when the player screen closes or the app goes to the background. */
    fun stop() {
        tuneJob?.cancel()
        waitJob?.cancel()
        tickJob?.cancel()
        endSession(atEnd = false)
        trace = null
        detach()
        pool.stopAll()
        queue.clear()
        standbyQueue = emptyList()
        _state.update { PlaybackState(tuneCount = it.tuneCount) }
    }

    private fun tuneTo(channel: Channel, surf: Int) {
        if (surf != 0) direction = surf
        standbyTarget = null
        if (current?.id != channel.id) previous = current
        current = channel
        tuneJob?.cancel()
        waitJob?.cancel()
        tuneJob = scope.launch { tuneNow(channel) }
    }

    private suspend fun tuneNow(channel: Channel) {
        endSession(atEnd = false)
        trace = TuneTrace.begin()
        _state.update { PlaybackState(channel = channel, status = TuneStatus.TUNING, tuneCount = it.tuneCount + 1) }
        settings.set(Settings.lastChannel(channel.profileId), channel.id)

        val now = time.nowUtcMs()
        val slot = slotAt(channel.id, now, scheduleIfMissing = true)
        if (slot == null) {
            stopActive()
            _state.update { it.copy(status = TuneStatus.NO_SCHEDULE, message = "Nothing is scheduled. Check the channel's lineup.") }
            return
        }
        if (slot.kind == SlotKind.OFF_AIR) {
            offAir(channel, slot)
            return
        }
        val program = resolve(slot, forceTranscode = false)
        if (program == null) {
            failAndRetry("This program can't be played. Is the server signed in?")
            return
        }
        val next = nextProgram(slot)

        val standby = pool.standby
        val swapped = standby.channelId == channel.id && standbyQueue.firstOrNull()?.slot?.startUtcMs == slot.startUtcMs
        val pooled = if (swapped) {
            pool.swap().also {
                queue.clear()
                queue.addAll(standbyQueue)
                standbyQueue = emptyList()
            }
        } else {
            pool.active.also {
                it.player.setMediaItems(listOfNotNull(program.mediaItem, next?.mediaItem), 0, now - slot.startUtcMs)
                it.player.prepare()
                queue.clear()
                queue.add(program)
                next?.let(queue::add)
            }
        }
        pooled.channelId = channel.id
        val player = pooled.player
        tracks.applyDefaults(player, channel.profileId)
        // The standby waited; catch up with the wall clock.
        if (swapped) player.seekTo(0, time.nowUtcMs() - slot.startUtcMs)
        attach(player)
        player.playWhenReady = true
        retriedSlot = Long.MIN_VALUE
        val playing = queue.first()
        _state.update {
            it.copy(
                status = TuneStatus.PLAYING,
                now = playing.info,
                next = queue.getOrNull(1)?.info ?: upcoming(slot),
                transcoded = playing.stream.transcoded,
            )
        }
        startTicker()
        AppLog.d(TAG, "Tuned ${channel.number} ${if (swapped) "from the standby" else "cold"}")
    }

    /** The slot airing at [nowUtcMs]; a channel without a schedule gets one first when [scheduleIfMissing]. */
    private suspend fun slotAt(channelId: Long, nowUtcMs: Long, scheduleIfMissing: Boolean): ScheduleSlotEntity? {
        scheduleDao.slotAt(channelId, nowUtcMs)?.takeIf { nowUtcMs < it.endUtcMs }?.let { return it }
        if (!scheduleIfMissing) return null
        withContext(io) { updater.update(channelId) }
        return scheduleDao.slotAt(channelId, nowUtcMs)?.takeIf { nowUtcMs < it.endUtcMs }
    }

    private suspend fun resolve(slot: ScheduleSlotEntity, forceTranscode: Boolean): Program? {
        val item = slot.mediaItemId?.let { itemDao.byId(it) } ?: return null
        val server = serverDao.byId(item.serverId) ?: return null
        val token = withContext(io) { tokens.get(server.jellyfinServerId) } ?: return null
        val decision = if (forceTranscode) {
            StreamDecision.Transcode(listOf("direct play failed"))
        } else {
            StreamPlanner.decide(item.streamInfo(), withContext(io) { probe.capabilities })
        }
        if (decision is StreamDecision.Transcode) AppLog.i(TAG, "Transcoding item ${item.id}: ${decision.reasons.joinToString()}")
        val stream = streams.stream(server.baseUrl, token, item.remoteId, item.mediaSourceId, transcode = decision is StreamDecision.Transcode)
        return Program(slot, item, server, token, stream)
    }

    /** The program right after [slot], or null when the channel goes off air or has nothing scheduled. */
    private suspend fun nextProgram(slot: ScheduleSlotEntity): Program? {
        val next = scheduleDao.slotsFrom(slot.channelId, slot.endUtcMs, 1).firstOrNull() ?: return null
        if (next.kind != SlotKind.PROGRAM || next.startUtcMs != slot.endUtcMs) return null
        return resolve(next, forceTranscode = false)
    }

    /** What follows [slot] for the banner, including an off-air stretch. */
    private suspend fun upcoming(slot: ScheduleSlotEntity): ProgramInfo? {
        val next = scheduleDao.slotsFrom(slot.channelId, slot.endUtcMs, 1).firstOrNull() ?: return null
        val item = next.mediaItemId?.let { itemDao.byId(it) } ?: return ProgramInfo(OFF_AIR_TITLE, null, next.startUtcMs, next.endUtcMs)
        return programInfo(item, next.startUtcMs, next.endUtcMs)
    }

    private suspend fun offAir(channel: Channel, slot: ScheduleSlotEntity) {
        stopActive()
        trace = null
        _state.update {
            it.copy(
                status = TuneStatus.OFF_AIR,
                now = ProgramInfo(OFF_AIR_TITLE, null, slot.startUtcMs, slot.endUtcMs),
                next = upcoming(slot),
            )
        }
        waitJob = scope.launch {
            delay(slot.endUtcMs - time.nowUtcMs() + RETUNE_GRACE_MS)
            tuneNow(channel)
        }
    }

    private fun failAndRetry(message: String) {
        endSession(atEnd = false)
        stopActive()
        trace = null
        _state.update { it.copy(status = TuneStatus.ERROR, message = message) }
        val channel = current ?: return
        waitJob?.cancel()
        waitJob = scope.launch {
            delay(RETRY_MS)
            tuneNow(channel)
        }
    }

    private suspend fun retryAsHls(failed: Program) {
        val player = _player.value ?: return
        val program = resolve(failed.slot, forceTranscode = true) ?: return failAndRetry("This program can't be played.")
        queue[0] = program
        player.setMediaItems(queue.map { it.mediaItem }, 0, time.nowUtcMs() - program.slot.startUtcMs)
        player.prepare()
        player.playWhenReady = true
        _state.update { it.copy(status = TuneStatus.PLAYING, transcoded = true, message = null) }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(TICK_MS)
                tick(++ticks)
            }
        }
    }

    /** Every 10 s a progress report; every 20 s a standby refresh. */
    private fun tick(ticks: Int) {
        val player = _player.value ?: return
        if (ticks % PROGRESS_TICKS == 0) session?.let { reporter.progress(it, player.currentPosition, paused = !player.isPlaying) }
        if (ticks % STANDBY_TICKS == 0) refreshStandby()
    }

    private fun refreshStandby() {
        val target = standbyTarget?.let { id -> lineup.firstOrNull { it.id == id } } ?: neighbor(direction) ?: return
        scope.launch { prewarm(target) }
    }

    /** Prepares [channel]'s program on the standby player without playing it. */
    private suspend fun prewarm(channel: Channel) {
        if (channel.id == current?.id) return
        val standby = pool.standby
        val now = time.nowUtcMs()
        val slot = slotAt(channel.id, now, scheduleIfMissing = false)?.takeIf { it.kind == SlotKind.PROGRAM }
        if (slot == null) {
            pool.stop(standby)
            standbyQueue = emptyList()
            return
        }
        if (standby.channelId == channel.id && standbyQueue.firstOrNull()?.slot?.startUtcMs == slot.startUtcMs) {
            // Keep its buffer near the live position.
            standby.player.seekTo(0, now - slot.startUtcMs)
            return
        }
        val program = resolve(slot, forceTranscode = false) ?: return
        val next = nextProgram(slot)
        pool.quiet(standby, withContext(io) { probe.capabilities }.concurrentVideoDecoders)
        standby.player.setMediaItems(listOfNotNull(program.mediaItem, next?.mediaItem), 0, now - slot.startUtcMs)
        standby.player.prepare()
        standby.channelId = channel.id
        standbyQueue = listOfNotNull(program, next)
        AppLog.d(TAG, "Standby ready on ${channel.number}")
    }

    private fun neighbor(step: Int): Channel? {
        if (lineup.isEmpty()) return null
        val index = lineup.indexOfFirst { it.id == current?.id }
        if (index < 0) return lineup.first()
        return lineup[Math.floorMod(index + step, lineup.size)]
    }

    private fun startSession(program: Program, positionMs: Long) {
        val profileId = current?.profileId ?: return
        session = reporter.start(
            item = program.item,
            server = program.server,
            accessToken = program.token,
            playSessionId = program.stream.playSessionId,
            transcoded = program.stream.transcoded,
            channelId = program.slot.channelId,
            profileId = profileId,
            positionMs = positionMs,
        )
    }

    /** Reports the stop of the program that was playing; [atEnd] when it played to its end. */
    private fun endSession(atEnd: Boolean, program: Program? = queue.firstOrNull()) {
        val ended = session ?: return
        session = null
        val position = if (atEnd) program?.item?.durationMs ?: 0 else _player.value?.currentPosition ?: 0
        reporter.stop(ended, position)
    }

    private fun stopActive() {
        detach()
        pool.stop(pool.active)
        queue.clear()
    }

    private fun attach(player: Player) {
        val old = _player.value
        if (old === player) return
        old?.removeListener(listener)
        player.addListener(listener)
        _player.value = player
    }

    private fun detach() {
        _player.value?.removeListener(listener)
        _player.value = null
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            val player = _player.value ?: return
            val finished = queue.removeFirstOrNull()
            endSession(atEnd = true, program = finished)
            if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
            val program = queue.firstOrNull() ?: return

            val expected = time.nowUtcMs() - program.slot.startUtcMs
            val drift = expected - player.currentPosition
            val corrected = drift > MAX_DRIFT_MS
            if (corrected) player.seekTo(expected)
            AppLog.i(TAG, "Next program on ${current?.number}: ${drift} ms behind the schedule${if (corrected) ", caught up" else ""}")

            startSession(program, maxOf(expected, 0))
            retriedSlot = Long.MIN_VALUE
            _state.update { it.copy(now = program.info, next = null, transcoded = program.stream.transcoded) }
            scope.launch { queueAfter(program) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                // Nothing more is queued: the channel goes off air, or the schedule ran out.
                Player.STATE_ENDED -> current?.let { channel ->
                    endSession(atEnd = true)
                    tuneJob?.cancel()
                    tuneJob = scope.launch { tuneNow(channel) }
                }
                else -> {
                    val buffering = playbackState == Player.STATE_BUFFERING
                    if (buffering != _state.value.buffering) _state.update { it.copy(buffering = buffering) }
                }
            }
        }

        override fun onRenderedFirstFrame() {
            val tuned = trace ?: return
            trace = null
            val ms = tuned.end()
            val program = queue.firstOrNull() ?: return
            AppLog.i(TAG, "Tune: first frame on ${current?.number} after $ms ms (${if (program.stream.transcoded) "HLS" else "direct play"})")
            if (session == null) startSession(program, _player.value?.currentPosition ?: 0)
            refreshStandby()
        }

        override fun onPlayerError(error: PlaybackException) {
            val program = queue.firstOrNull() ?: return
            AppLog.w(TAG, "Playback failed on ${current?.number}: ${error.errorCodeName}", error)
            if (!program.stream.transcoded && error.errorCode in FormatErrors && retriedSlot != program.slot.startUtcMs) {
                retriedSlot = program.slot.startUtcMs
                endSession(atEnd = false)
                scope.launch { retryAsHls(program) }
            } else {
                failAndRetry("This program can't be played right now (${error.errorCodeName}). Trying again…")
            }
        }
    }

    private suspend fun queueAfter(program: Program) {
        val next = nextProgram(program.slot)
        if (queue.lastOrNull() !== program) return
        val player = _player.value ?: return
        if (next != null) {
            player.addMediaItem(next.mediaItem)
            queue.add(next)
            _state.update { it.copy(next = next.info) }
        } else {
            _state.update { it.copy(next = upcoming(program.slot)) }
        }
    }


    private companion object {
        const val TAG = "Player"
        const val OFF_AIR_TITLE = "Off air"
        const val MAX_DRIFT_MS = 3_000L
        const val RETUNE_GRACE_MS = 500L
        const val RETRY_MS = 10_000L
        const val TICK_MS = 1_000L
        const val HANDOVER_MS = 1_500L

        /** Every 10 s at one tick a second. */
        const val PROGRESS_TICKS = 10
        const val STANDBY_TICKS = 20
    }
}

private fun MediaItemEntity.streamInfo() = StreamInfo(container, videoCodec, audioCodec, width, height, videoBitDepth)

private fun programInfo(item: MediaItemEntity, startUtcMs: Long, endUtcMs: Long) = ProgramInfo(
    title = programTitle(item.title, item.showTitle),
    detail = programDetail(item.title, item.showTitle, item.season, item.episode, item.year),
    startUtcMs = startUtcMs,
    endUtcMs = endUtcMs,
)

/** The text track type, for screens that don't depend on Media3 directly. */
const val TRACK_TYPE_TEXT = C.TRACK_TYPE_TEXT

/** The audio track type, for screens that don't depend on Media3 directly. */
const val TRACK_TYPE_AUDIO = C.TRACK_TYPE_AUDIO
