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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Artwork
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ScheduleRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WatchAccount
import tv.anstelle.core.data.channels.ScheduleUpdater
import tv.anstelle.core.data.profiles.Restriction
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
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.player.CapabilityProbe
import tv.anstelle.core.player.FontCache
import tv.anstelle.core.player.JellyfinStreams
import tv.anstelle.core.player.PlayerPool
import tv.anstelle.core.player.PooledPlayer
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
    /** "S1E4 - Pilot", a movie's year, or null. */
    val detail: String?,
    val startUtcMs: Long,
    val endUtcMs: Long,
    /** The show's or movie's title logo, which the banner shows in place of [title]; null when there is none. */
    val logoUrl: String? = null,
    /** True for an episode, false for a movie or anything else without a show. */
    val isEpisode: Boolean = false,
    /** Something the profile may not watch: banners blur the title and logo. */
    val restricted: Boolean = false,
)

/** [RESTRICTED]: the profile may not watch the program on now ([PlaybackState.restriction] says why), so the screen stays black. */
enum class TuneStatus { IDLE, TUNING, PLAYING, OFF_AIR, RESTRICTED, NO_SCHEDULE, ERROR }

data class PlaybackState(
    val channel: Channel? = null,
    val status: TuneStatus = TuneStatus.IDLE,
    val buffering: Boolean = false,
    val now: ProgramInfo? = null,
    val next: ProgramInfo? = null,
    /** The server, play method and picture and sound of the program on now. */
    val stream: StreamFacts? = null,
    val message: String? = null,
    /** Why the program on now is blacked out, while [status] is [TuneStatus.RESTRICTED]. */
    val restriction: Restriction? = null,
    /** Goes up with every tune, so the screen knows to show the info banner. */
    val tuneCount: Int = 0,
)

/** One scheduled program, ready to play as [account]. */
private class Program(
    val slot: ScheduleSlotEntity,
    val item: MediaItemEntity,
    val server: MediaServerEntity,
    val account: WatchAccount,
    val stream: ProgramStream,
    val logoUrl: String?,
) {
    val mediaItem: MediaItem = stream.mediaItem("${slot.channelId}@${slot.startUtcMs}")

    val info: ProgramInfo = programInfo(item, slot.startUtcMs, slot.endUtcMs, logoUrl)

    val facts: StreamFacts = StreamFacts.of(item, server.name, stream.transcoded)

    /** The same program after its airing moved by [deltaMs]. */
    fun shifted(deltaMs: Long) = Program(slot.copy(startUtcMs = slot.startUtcMs + deltaMs, endUtcMs = slot.endUtcMs + deltaMs), item, server, account, stream, logoUrl)
}

/**
 * Plays channels: what a channel airs comes from SQLite and the stream URL is built locally,
 * so tuning makes no server call.
 *
 * - **Back to back:** the player's playlist holds the program on now and the next one, so
 *   one runs into the other without a gap. At each transition the following program is
 *   queued, and when the new program is more than [MAX_DRIFT_MS] behind the wall clock (after
 *   buffering, say) it seeks ahead. A program with ASS subtitles isn't queued that way
 *   ([ProgramStream.chainsWith]): it starts on its own when the one before it ends.
 * - **Tracks:** each program starts on the watching profile's audio and subtitle defaults.
 * - **Who plays:** each program streams as the profile's Jellyfin user on its server
 *   ([ProfileRepository.watchAccount]), and its watch history goes there.
 * - **Pool:** the channel in the surfing direction (or the one focused in the guide) is
 *   pre-warmed on the standby player, and tuning to it swaps the players.
 * - **Seeking** works as in any video, and the channel's schedule moves with it: the airing
 *   now starts where the new position puts it, and everything after it on the channel moves by
 *   the same amount, so the guide and the next program follow what is on screen.
 * - **Off air** shows until the off-air stretch ends, then the channel tunes itself again.
 * - **Restricted:** channels are the same for every profile, so a program over the watching
 *   profile's maturity rating (or unrated, under a limit), or one its Jellyfin user can't see,
 *   still airs. The screen stays black, says why, and tunes again when the program ends; it is
 *   never queued or pre-warmed.
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
    private val schedules: ScheduleRepository,
    private val itemDao: MediaItemDao,
    private val serverDao: MediaServerDao,
    private val tokens: TokenStore,
    private val updater: ScheduleUpdater,
    private val reporter: PlaybackReporter,
    private val tracks: TrackChoices,
    private val fonts: FontCache,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
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

    /** The watching profile, read at every tune: its defaults, history and what it may watch apply. */
    private var profileId = 0L
    private var access = ProfileAccess.OPEN
    private var prefs = TrackPrefs()

    private var tuneJob: Job? = null
    private var waitJob: Job? = null
    private var tickJob: Job? = null
    private var handoverJob: Job? = null

    /** Schedule moves are written one at a time, in the order the seeks happened. */
    private val shifts = Mutex()

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

    /** Where the program on now is, in its media; 0 while nothing plays. */
    val positionMs: Long get() = _player.value?.currentPosition ?: 0

    /**
     * Seeks to [positionMs] in the program on now (kept short of its end, so the seek never
     * runs into the next one). The channel's schedule moves to match.
     */
    fun seekTo(positionMs: Long) {
        val player = _player.value ?: return
        val program = queue.firstOrNull() ?: return
        if (_state.value.status != TuneStatus.PLAYING) return
        val length = program.slot.endUtcMs - program.slot.startUtcMs
        val target = positionMs.coerceIn(0, (length - SEEK_END_MARGIN_MS).coerceAtLeast(0))
        player.seekTo(player.currentMediaItemIndex, target)
        retime(program, time.nowUtcMs() - target - program.slot.startUtcMs)
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    /** Starts the program on now over from its beginning. */
    fun restart() = seekTo(0)

    /** Moves [program]'s airing, and everything after it on the channel, by [deltaMs]. */
    private fun retime(program: Program, deltaMs: Long) {
        if (kotlin.math.abs(deltaMs) < MIN_SHIFT_MS) return
        val moved = queue.map { it.shifted(deltaMs) }
        queue.clear()
        queue.addAll(moved)
        _state.update { state ->
            state.copy(
                now = moved.first().info,
                next = state.next?.let { it.copy(startUtcMs = it.startUtcMs + deltaMs, endUtcMs = it.endUtcMs + deltaMs) },
            )
        }
        val channelId = program.slot.channelId
        val from = program.slot.startUtcMs
        scope.launch {
            shifts.withLock { withContext(io + NonCancellable) { schedules.shift(channelId, from, deltaMs) } }
        }
        AppLog.i(TAG, "Seek on ${current?.number}: the schedule moved ${deltaMs / 1000} s")
    }

    fun trackOptions(type: Int): List<TrackOption> = _player.value?.let { tracks.options(it, type) }.orEmpty()

    fun selectTrack(type: Int, option: TrackOption) {
        _player.value?.let { tracks.select(it, type, option) }
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
        current = channel
        tuneJob?.cancel()
        waitJob?.cancel()
        tuneJob = scope.launch { tuneNow(channel) }
    }

    /**
     * Tunes to what [channel] airs now or, when [after] is set because the program that ended
     * then had nothing queued behind it, to the program that follows it.
     */
    private suspend fun tuneNow(channel: Channel, after: Long? = null) {
        endSession(atEnd = false)
        trace = TuneTrace.begin()
        _state.update {
            PlaybackState(channel = channel, status = TuneStatus.TUNING, tuneCount = it.tuneCount + if (after == null) 1 else 0)
        }
        val profile = profiles.observeActiveProfile().first()
        if (profile.id != profileId) {
            // The standby was readied as the profile before, as its Jellyfin user.
            pool.stop(pool.standby)
            standbyQueue = emptyList()
        }
        profileId = profile.id
        access = profiles.access(profile.id)
        settings.set(Settings.lastChannel(profileId), channel.id)
        prefs = tracks.prefs(profileId)

        val now = time.nowUtcMs()
        val slot = slotAt(channel.id, maxOf(now, after ?: now), scheduleIfMissing = true)
        if (slot == null) {
            stopActive()
            _state.update { it.copy(status = TuneStatus.NO_SCHEDULE, message = "Nothing is scheduled. Check the channel's lineup.") }
            return
        }
        if (slot.kind == SlotKind.OFF_AIR) {
            offAir(channel, slot)
            return
        }
        val item = slot.mediaItemId?.let { itemDao.byId(it) }
        if (item != null && !allowed(item)) {
            restricted(channel, slot, item)
            return
        }
        val program = resolve(slot, forceTranscode = false)
        if (program == null) {
            failAndRetry("This program can't be played. Check the server's and the profile's sign-in.")
            return
        }
        val next = nextProgram(slot)?.takeIf { program.stream.chainsWith(it.stream) }

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
                val programs = listOfNotNull(program, next)
                load(it, programs, slot)
                queue.clear()
                queue.addAll(programs)
            }
        }
        pooled.channelId = channel.id
        val player = pooled.player
        tracks.applyDefaults(pooled, prefs)
        // The standby waited; catch up with the wall clock.
        if (swapped) player.seekTo(0, (time.nowUtcMs() - slot.startUtcMs).coerceAtLeast(0))
        attach(player)
        player.playWhenReady = true
        retriedSlot = Long.MIN_VALUE
        val playing = queue.first()
        _state.update {
            it.copy(
                status = TuneStatus.PLAYING,
                now = playing.info,
                next = queue.getOrNull(1)?.info ?: upcoming(slot),
                stream = playing.facts,
            )
        }
        startTicker()
        AppLog.d(TAG, "Tuned ${channel.number} ${if (swapped) "from the standby" else "cold"}")
    }

    /**
     * Prepares [programs] on [pooled] from [slot]'s live position. Fonts for side-loaded ASS
     * subtitles are fetched first and handed to libass once the playlist is set. Returns false,
     * leaving the player alone, when [stillWanted] no longer holds after the fonts arrive.
     */
    private suspend fun load(
        pooled: PooledPlayer,
        programs: List<Program>,
        slot: ScheduleSlotEntity,
        stillWanted: () -> Boolean = { true },
    ): Boolean {
        val fontFiles = fonts.load(programs.first().stream)
        if (!stillWanted()) return false
        pooled.player.setMediaItems(programs.map { it.mediaItem }, 0, (time.nowUtcMs() - slot.startUtcMs).coerceAtLeast(0))
        pooled.subtitles.addFonts(fontFiles)
        pooled.player.prepare()
        return true
    }

    /** The slot airing at [nowUtcMs]; a channel without a schedule gets one first when [scheduleIfMissing]. */
    private suspend fun slotAt(channelId: Long, nowUtcMs: Long, scheduleIfMissing: Boolean): ScheduleSlotEntity? {
        scheduleDao.slotAt(channelId, nowUtcMs)?.takeIf { nowUtcMs < it.endUtcMs }?.let { return it }
        if (!scheduleIfMissing) return null
        withContext(io) { updater.update(channelId) }
        return scheduleDao.slotAt(channelId, nowUtcMs)?.takeIf { nowUtcMs < it.endUtcMs }
    }

    /** [slot]'s program, streaming as the watching profile's Jellyfin user; null when no one is signed in to play it as. */
    private suspend fun resolve(slot: ScheduleSlotEntity, forceTranscode: Boolean): Program? {
        val item = slot.mediaItemId?.let { itemDao.byId(it) } ?: return null
        val server = serverDao.byId(item.serverId) ?: return null
        val token = withContext(io) { tokens.get(server.jellyfinServerId) } ?: return null
        val account = profiles.watchAccount(profileId, server, token) ?: return null
        val decision = if (forceTranscode) {
            StreamDecision.Transcode(listOf("direct play failed"))
        } else {
            StreamPlanner.decide(item.streamInfo(), withContext(io) { probe.capabilities })
        }
        if (decision is StreamDecision.Transcode) AppLog.i(TAG, "Transcoding item ${item.id}: ${decision.reasons.joinToString()}")
        val stream = streams.stream(
            server.baseUrl, account.accessToken, account.device, item.remoteId, item.mediaSourceId,
            transcode = decision is StreamDecision.Transcode, tracks = item.tracks, prefs = prefs,
        )
        return Program(slot, item, server, account, stream, logoUrl(item, server))
    }

    /**
     * The program right after [slot], or null when the channel goes off air, has nothing
     * scheduled, or airs something the profile may not watch.
     */
    private suspend fun nextProgram(slot: ScheduleSlotEntity): Program? {
        val next = scheduleDao.slotsFrom(slot.channelId, slot.endUtcMs, 1).firstOrNull() ?: return null
        if (next.kind != SlotKind.PROGRAM || next.startUtcMs != slot.endUtcMs) return null
        return resolve(next, forceTranscode = false)?.takeIf { allowed(it.item) }
    }

    private fun allowed(item: MediaItemEntity) = access.allows(item.id, item.ratingScore)

    /** What follows [slot] for the banner, including an off-air stretch. */
    private suspend fun upcoming(slot: ScheduleSlotEntity): ProgramInfo? = upcomingAt(slot.channelId, slot.endUtcMs)

    /**
     * What airs on [channelId] from [fromUtcMs] for the banner, including an off-air stretch. A
     * program the profile may not watch is marked, and shown as one stretch with the restricted
     * programs after it.
     */
    private suspend fun upcomingAt(channelId: Long, fromUtcMs: Long): ProgramInfo? {
        val next = scheduleDao.slotsFrom(channelId, fromUtcMs, 1).firstOrNull() ?: return null
        val item = next.mediaItemId?.let { itemDao.byId(it) } ?: return ProgramInfo(OFF_AIR_TITLE, null, next.startUtcMs, next.endUtcMs)
        val restricted = !allowed(item)
        val end = if (restricted) runEnd(next) else next.endUtcMs
        return programInfo(item, next.startUtcMs, end, serverDao.byId(item.serverId)?.let { logoUrl(item, it) }, restricted)
    }

    /** [item]'s title logo, or its show's for an episode. */
    private suspend fun logoUrl(item: MediaItemEntity, server: MediaServerEntity): String? {
        val series = item.seriesRemoteId?.let { itemDao.byRemoteId(item.serverId, it) }
        return Artwork.of(server.baseUrl, item, series).logoUrl
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

    /**
     * Blacks out [item], airing on [slot], and every restricted program straight after it, as
     * one stretch; when the stretch ends the channel tunes itself again.
     */
    private suspend fun restricted(channel: Channel, slot: ScheduleSlotEntity, item: MediaItemEntity) {
        stopActive()
        trace = null
        val until = runEnd(slot)
        val server = serverDao.byId(item.serverId)
        val restriction = Restriction.of(item.id, item.ratingScore, access)
        _state.update {
            it.copy(
                status = TuneStatus.RESTRICTED,
                // The picture is blacked out; the banner blurs the title and logo.
                now = programInfo(item, slot.startUtcMs, until, server?.let { s -> logoUrl(item, s) }, restricted = true),
                next = upcomingAt(slot.channelId, until),
                restriction = restriction,
            )
        }
        AppLog.i(TAG, "Channel ${channel.number}: ${restriction?.title} until ${until - slot.startUtcMs} ms from item ${item.id}'s start")
        waitJob = scope.launch {
            delay(until - time.nowUtcMs() + RETUNE_GRACE_MS)
            tuneNow(channel)
        }
    }

    /** Where the run of restricted programs [slot] starts ends: the first program the profile may watch, or off air. */
    private suspend fun runEnd(slot: ScheduleSlotEntity): Long {
        if (access.isOpen) return slot.endUtcMs
        return withContext(io) { scheduleDao.restrictedUntil(slot.channelId, slot.endUtcMs, access.limit, profileId) }?.coerceAtLeast(slot.endUtcMs) ?: slot.endUtcMs
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
        val pooled = pool.active
        if (_player.value !== pooled.player) return
        val program = resolve(failed.slot, forceTranscode = true) ?: return failAndRetry("This program can't be played.")
        val programs = listOfNotNull(program, queue.getOrNull(1)?.takeIf { program.stream.chainsWith(it.stream) })
        if (!load(pooled, programs, program.slot) { pool.active === pooled && _player.value === pooled.player }) return
        queue.clear()
        queue.addAll(programs)
        pooled.player.playWhenReady = true
        _state.update { it.copy(status = TuneStatus.PLAYING, stream = program.facts, message = null) }
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

    /** Every second a sample of what played; every 10 s a progress report; every 20 s a standby refresh. */
    private fun tick(ticks: Int) {
        val player = _player.value ?: return
        if (player.isPlaying) session?.let { reporter.playing(it, player.currentPosition) }
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
        val program = resolve(slot, forceTranscode = false)?.takeIf { allowed(it.item) }
        if (program == null) {
            pool.stop(standby)
            standbyQueue = emptyList()
            return
        }
        val next = nextProgram(slot)?.takeIf { program.stream.chainsWith(it.stream) }
        pool.quiet(standby, withContext(io) { probe.capabilities }.concurrentVideoDecoders)
        val programs = listOfNotNull(program, next)
        // A tune may swap the pool while fonts load; then this player is on screen.
        if (!load(standby, programs, slot) { pool.standby === standby }) return
        standby.channelId = channel.id
        standbyQueue = programs
        AppLog.d(TAG, "Standby ready on ${channel.number}")
    }

    private fun neighbor(step: Int): Channel? {
        if (lineup.isEmpty()) return null
        val index = lineup.indexOfFirst { it.id == current?.id }
        if (index < 0) return lineup.first()
        return lineup[Math.floorMod(index + step, lineup.size)]
    }

    private fun startSession(program: Program, positionMs: Long) {
        if (current == null) return
        session = reporter.start(
            item = program.item,
            server = program.server,
            account = program.account,
            playSessionId = program.stream.playSessionId,
            transcoded = program.stream.transcoded,
            channelId = program.slot.channelId,
            profileId = profileId,
            positionMs = positionMs,
            playing = _player.value?.isPlaying == true,
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
            tracks.applyDefaults(pool.active, prefs)
            retriedSlot = Long.MIN_VALUE
            _state.update { it.copy(now = program.info, next = null, stream = program.facts) }
            scope.launch { queueAfter(program) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                // Nothing more is queued: the next program wasn't chained, the channel goes off
                // air, or the schedule ran out.
                Player.STATE_ENDED -> current?.let { channel ->
                    val ended = queue.firstOrNull()?.slot?.endUtcMs
                    endSession(atEnd = true)
                    tuneJob?.cancel()
                    tuneJob = scope.launch { tuneNow(channel, after = ended) }
                }
                else -> {
                    val buffering = playbackState == Player.STATE_BUFFERING
                    if (buffering != _state.value.buffering) _state.update { it.copy(buffering = buffering) }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            session?.let { reporter.playingChanged(it, isPlaying) }
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
        if (next != null && program.stream.chainsWith(next.stream)) {
            player.addMediaItem(next.mediaItem)
            queue.add(next)
            _state.update { it.copy(next = next.info) }
        } else {
            _state.update { it.copy(next = next?.info ?: upcoming(program.slot)) }
        }
    }


    private companion object {
        const val TAG = "Player"
        /** The banner's NOW or NEXT title off air, uppercase like the off-air mark under it. */
        const val OFF_AIR_TITLE = "OFF AIR"
        const val MAX_DRIFT_MS = 3_000L

        /** A seek stops this far before the program's end, so it never runs into the next one. */
        const val SEEK_END_MARGIN_MS = 3_000L

        /** Smaller moves than this leave the schedule alone. */
        const val MIN_SHIFT_MS = 1_000L
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

private fun programInfo(item: MediaItemEntity, startUtcMs: Long, endUtcMs: Long, logoUrl: String? = null, restricted: Boolean = false) = ProgramInfo(
    title = programTitle(item.title, item.showTitle),
    detail = programDetail(item.title, item.showTitle, item.season, item.episode, item.year),
    startUtcMs = startUtcMs,
    endUtcMs = endUtcMs,
    logoUrl = logoUrl,
    isEpisode = item.showTitle != null,
    restricted = restricted,
)

/** The text track type, for screens that don't depend on Media3 directly. */
const val TRACK_TYPE_TEXT = C.TRACK_TYPE_TEXT

/** The audio track type, for screens that don't depend on Media3 directly. */
const val TRACK_TYPE_AUDIO = C.TRACK_TYPE_AUDIO
