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
 * video frames are already done. Main thread only.
 */
@Singleton
@OptIn(UnstableApi::class)
class PlayerPool @Inject constructor(
    private val factory: PlayerFactory,
) {
    private var players: Array<PooledPlayer>? = null

    private fun players(): Array<PooledPlayer> = players ?: arrayOf(factory.create(), factory.create()).also {
        it[1].loadControl.standby = true
        players = it
    }

    @get:MainThread
    val active: PooledPlayer get() = players()[0]

    @get:MainThread
    val standby: PooledPlayer get() = players()[1]

    /** Makes the standby the active player. The old active player stops and becomes the standby. */
    @MainThread
    fun swap(): PooledPlayer {
        val pair = players()
        pair.reverse()
        pair[0].loadControl.standby = false
        pair[1].loadControl.standby = true
        stop(pair[1])
        return pair[0]
    }

    /**
     * Prepares the standby without decoding: audio stays off, and video too unless the
     * device can run a second video decoder (Fire TV sticks can't).
     */
    @MainThread
    fun quiet(player: PooledPlayer, concurrentVideoDecoders: Boolean) {
        player.player.playWhenReady = false
        player.player.trackSelectionParameters = player.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !concurrentVideoDecoders)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    @MainThread
    fun stop(player: PooledPlayer) {
        player.player.stop()
        player.player.clearMediaItems()
        player.channelId = null
    }

    /** Stops both players; they are kept for the next time something plays. */
    @MainThread
    fun stopAll() {
        players?.forEach(::stop)
    }

    @MainThread
    fun release() {
        players?.forEach(PooledPlayer::release)
        players = null
    }
}
