package tv.anstelle.core.player

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

    @MainThread
    fun release() {
        SubtitleOverlays.unregister(player)
        player.release()
        subtitles.release()
    }
}

/**
 * Two ExoPlayers: the one on screen and a standby pre-warmed for the next likely channel.
 * Tuning to the standby's channel swaps the two instead of preparing from scratch, so the
 * connection, container parsing and (when the device can run two video decoders) the first
 * video frames are already done. A player whose libass holds more than [FONT_BUDGET_BYTES] of
 * fonts is replaced by a new one the next time it stops, since libass never lets a font go.
 * Main thread only.
 */
@Singleton
@OptIn(UnstableApi::class)
class PlayerPool @Inject constructor(
    private val factory: PlayerFactory,
) {
    private var activeSlot: PooledPlayer? = null
    private var standbySlot: PooledPlayer? = null

    @get:MainThread
    val active: PooledPlayer get() = activeSlot ?: factory.create().also { activeSlot = it }

    @get:MainThread
    val standby: PooledPlayer get() = standbySlot ?: factory.create().also {
        it.loadControl.standby = true
        standbySlot = it
    }

    @get:MainThread
    val preparedStandby: PooledPlayer? get() = standbySlot

    @MainThread
    fun releaseStandby() {
        standbySlot?.release()
        standbySlot = null
    }

    /** Makes the standby the active player. The old active player stops and becomes the standby. */
    @MainThread
    fun swap(): PooledPlayer {
        val next = standby
        val previous = active
        activeSlot = next
        standbySlot = previous
        next.loadControl.standby = false
        previous.loadControl.standby = true
        stop(previous)
        return next
    }

    /**
     * Prepares a muted standby: video stays off unless the
     * device can run a second video decoder (Fire TV sticks can't).
     */
    @MainThread
    fun quiet(player: PooledPlayer, concurrentVideoDecoders: Boolean) {
        player.player.playWhenReady = false
        player.player.volume = 0f
        player.player.trackSelectionParameters = player.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, factory.lowMemory || !concurrentVideoDecoders)
            // Audio keeps the demuxer loading even on a stick with one video decoder.
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
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
        if (player !== activeSlot && player !== standbySlot) return player
        val budget = if (factory.lowMemory) 16L * 1024 * 1024 else FONT_BUDGET_BYTES
        if (player.subtitles.fontBytes <= budget) return player
        val isStandby = player === standbySlot
        player.release()
        return factory.create().also {
            it.loadControl.standby = isStandby
            if (isStandby) standbySlot = it else activeSlot = it
        }
    }

    /** Stops the active player for reuse and releases the idle standby. */
    @MainThread
    fun stopAll() {
        activeSlot?.let(::stop)
        releaseStandby()
    }

    @MainThread
    fun release() {
        activeSlot?.release()
        activeSlot = null
        releaseStandby()
    }

    private companion object {
        /** A few shows' embedded fonts (anime often carries 10–30 MB of them). */
        const val FONT_BUDGET_BYTES = 48L * 1024 * 1024
    }
}
