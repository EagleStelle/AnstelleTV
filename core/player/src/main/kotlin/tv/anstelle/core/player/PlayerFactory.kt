package tv.anstelle.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the ExoPlayer instances of the [PlayerPool].
 *
 * All media requests go through the app's shared [OkHttpClient], with the server's
 * `Authorization` header added by [StreamAuth]. Platform decoders come first; the FFmpeg
 * audio renderer only steps in when the device can't decode a format itself (TrueHD or DTS
 * on a phone, say). Passthrough to a receiver follows the device's `AudioCapabilities`.
 */
@Singleton
@OptIn(UnstableApi::class)
class PlayerFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    auth: StreamAuth,
) {
    private val dataSourceFactory = ResolvingDataSource.Factory(OkHttpDataSource.Factory(okHttpClient)) { spec ->
        val header = auth.headerFor(spec.uri.toString()) ?: return@Factory spec
        spec.withAdditionalHeaders(mapOf("Authorization" to header))
    }

    fun create(): PooledPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = PoolLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                .build(),
        )
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
        return PooledPlayer(player, loadControl)
    }

    private companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 50_000
        const val START_BUFFER_MS = 1_000
        const val REBUFFER_MS = 2_000
    }
}

/**
 * The pool's load control: the normal buffer while a player is on screen, a few seconds
 * while it waits as the pre-warmed standby, so the standby holds little memory. Every
 * method is forwarded by hand: Kotlin delegation skips the interface's Java default methods.
 */
@OptIn(UnstableApi::class)
class PoolLoadControl(private val base: DefaultLoadControl) : LoadControl {
    @Volatile var standby: Boolean = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (standby && parameters.bufferedDurationUs >= STANDBY_BUFFER_US) return false
        return base.shouldContinueLoading(parameters)
    }

    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = base.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = base.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = base.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    private companion object {
        const val STANDBY_BUFFER_US = 4_000_000L
    }
}
