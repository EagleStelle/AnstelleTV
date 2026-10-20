package tv.anstelle.core.player

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import tv.anstelle.core.model.TrackPrefs
import javax.inject.Inject
import javax.inject.Singleton

/** A player of the pool (or of on-demand playback) and the channel it is prepared for. */
@OptIn(UnstableApi::class)
class PooledPlayer(
    val player: ExoPlayer,
    internal val loadControl: PoolLoadControl,
    val trackSelector: LanguageTrackSelector,
    val subtitles: PlayerSubtitles,
) {
    /** Channel this player is prepared for, or null. */
    var channelId: Long? = null

    /** Resets per-program track picks and audio languages in one update, including any standby video limit. */
    @MainThread
    fun resetTrackParameters(prefs: TrackPrefs, videoDisabled: Boolean = false) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoDisabled)
            // Audio keeps the demuxer loading even on a stick with one video decoder.
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredAudioLanguages(*prefs.audioLanguages.toTypedArray())
            .build()
    }

    @MainThread
    fun release() {
        SubtitleOverlays.unregister(player)
        player.release()
        subtitles.release()
    }
}

/**
 * The ExoPlayer on screen and up to [STANDBYS] standbys pre-warmed for the next likely channels.
 * Tuning to a standby's channel swaps it with the active player instead of preparing from
 * scratch, so the connection, container parsing and (when the device can run two video decoders)
 * the first video frames are already done. A player whose libass holds more than
 * [FONT_BUDGET_BYTES] of fonts is replaced by a new one the next time it stops, since libass
 * never lets a font go. Main thread only.
 */
@Singleton
@OptIn(UnstableApi::class)
class PlayerPool @Inject constructor(
    private val factory: PlayerFactory,
) {
    private var activeSlot: PooledPlayer? = null
    private val standbySlots = arrayOfNulls<PooledPlayer>(STANDBYS)

    @get:MainThread
    val active: PooledPlayer get() = activeSlot ?: factory.create(PlaybackMode.LIVE_TV).also { activeSlot = it }

    /** The standby in [slot], created when there is none. */
    @MainThread
    fun standby(slot: Int = 0): PooledPlayer = standbySlots[slot] ?: factory.create(PlaybackMode.LIVE_TV).also {
        it.loadControl.standby = true
        standbySlots[slot] = it
    }

    /** The standby in [slot], or null while there is none. */
    @MainThread
    fun preparedStandby(slot: Int = 0): PooledPlayer? = standbySlots[slot]

    @MainThread
    fun releaseStandby(slot: Int) {
        standbySlots[slot]?.release()
        standbySlots[slot] = null
    }

    /**
     * Makes [slot]'s standby the active player, seeking exactly again on screen. The old active
     * player stops and becomes that slot's standby.
     */
    @MainThread
    fun swap(slot: Int = 0): PooledPlayer {
        val next = standby(slot)
        val previous = active
        activeSlot = next
        standbySlots[slot] = previous
        next.loadControl.standby = false
        next.player.setSeekParameters(SeekParameters.DEFAULT)
        previous.loadControl.standby = true
        stop(previous)
        return next
    }

    /**
     * Prepares a muted standby: video stays off unless the device can run a second video
     * decoder (Fire TV sticks can't), and then only in the first slot, so at most two decoders
     * ever run. It seeks to keyframes, where its buffer starts, so every seek it makes, the
     * swap's included, stays inside that buffer.
     */
    @MainThread
    fun quiet(player: PooledPlayer, concurrentVideoDecoders: Boolean, prefs: TrackPrefs = TrackPrefs()) {
        player.player.playWhenReady = false
        player.player.volume = 0f
        player.player.setSeekParameters(SeekParameters.PREVIOUS_SYNC)
        player.trackSelector.setPrefs(prefs)
        // Apply defaults and decoder limits together; never enable an old standby's video first.
        player.resetTrackParameters(prefs, videoDisabled = factory.lowMemory || !concurrentVideoDecoders || player !== standbySlots[0])
    }

    /**
     * Keeps the paused [standby] at the keyframe before [liveMs], where its program airs now. It
     * holds only seconds of buffer, so it moves before falling half of that behind: a swap's seek
     * then lands inside the buffer instead of downloading again. A seek that lands on the
     * keyframe it already waits at does nothing.
     */
    @MainThread
    fun followLive(standby: PooledPlayer, liveMs: Long) {
        if (liveMs - standby.player.currentPosition >= standby.loadControl.standbyBufferUs / 2_000) standby.player.seekTo(0, liveMs)
    }

    @MainThread
    fun stop(player: PooledPlayer) {
        player.player.stop()
        player.player.clearMediaItems()
        player.channelId = null
        renewIfFull(player)
    }

    /**
     * The player to load a new channel into in [player]'s place: [player] itself, or a new one
     * once its libass holds more fonts than [FONT_BUDGET_BYTES] (the old one is released).
     */
    @MainThread
    fun renewIfFull(player: PooledPlayer): PooledPlayer {
        val slot = standbySlots.indexOf(player)
        if (player !== activeSlot && slot < 0) return player
        val budget = if (factory.lowMemory) 16L * 1024 * 1024 else FONT_BUDGET_BYTES
        if (player.subtitles.fontBytes <= budget) return player
        player.release()
        return factory.create(PlaybackMode.LIVE_TV).also {
            it.loadControl.standby = slot >= 0
            if (slot >= 0) standbySlots[slot] = it else activeSlot = it
        }
    }

    /** Stops the active player for reuse and releases the idle standbys. */
    @MainThread
    fun stopAll() {
        activeSlot?.let(::stop)
        releaseStandbys()
    }

    @MainThread
    fun releaseStandbys() = standbySlots.indices.forEach(::releaseStandby)

    @MainThread
    fun release() {
        activeSlot?.release()
        activeSlot = null
        releaseStandbys()
    }

    companion object {
        /** The channels either side of the one on screen while someone surfs. */
        const val STANDBYS = 2

        /** A few shows' embedded fonts (anime often carries 10–30 MB of them). */
        private const val FONT_BUDGET_BYTES = 48L * 1024 * 1024
    }
}
