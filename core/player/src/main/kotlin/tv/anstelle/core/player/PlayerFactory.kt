package tv.anstelle.core.player

import android.content.Context
import android.app.ActivityManager
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
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the ExoPlayer instances of the [PlayerPool].
 *
 * All media requests go through the app's shared [OkHttpClient], with the server's
 * `Authorization` header added by [StreamAuth]. Platform decoders come first; the FFmpeg
 * audio renderer only steps in when the device can't decode a format itself (TrueHD or DTS
 * on a phone, say). Passthrough to a receiver follows the device's `AudioCapabilities`.
 * Each player has its own libass ([PlayerSubtitles]) and picks tracks by language
 * ([LanguageTrackSelector]).
 */
@Singleton
@OptIn(UnstableApi::class)
class PlayerFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    auth: StreamAuth,
) {
    val lowMemory: Boolean by lazy {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        PlaybackBudget.isLowMemory(manager.isLowRamDevice, memory.totalMem, manager.memoryClass)
    }
    // Trace request-to-headers separately from container parsing and decoder work.
    // No URLs, credentials or item metadata are included in these sections.
    private val mediaClient = okHttpClient.newBuilder().eventListenerFactory {
        object : EventListener() {
            private var headers: TuneTrace? = null
            override fun callStart(call: Call) { headers = TuneTrace.begin("StreamHeaders") }
            override fun responseHeadersEnd(call: Call, response: Response) { finish() }
            override fun callFailed(call: Call, ioe: IOException) { finish() }
            override fun callEnd(call: Call) { finish() }
            private fun finish() { headers?.end(); headers = null }
        }
    }.build()
    private val dataSourceFactory = ResolvingDataSource.Factory(OkHttpDataSource.Factory(mediaClient)) { spec ->
        val header = auth.headerFor(spec.uri.toString()) ?: return@Factory spec
        spec.withAdditionalHeaders(mapOf("Authorization" to header))
    }

    fun create(): PooledPlayer {
        val subtitles = PlayerSubtitles()
        val renderersFactory = AssRenderersFactory(
            subtitles.handler,
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true),
        )
        val trackSelector = LanguageTrackSelector(context)
        val loadControl = PoolLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                // The default target is about 125 MB of heap per player; a stick's heap is 256 MB.
                .setTargetBufferBytes(if (lowMemory) 32 * 1024 * 1024 else TARGET_BUFFER_BYTES)
                .build(),
            standbyBufferUs = if (lowMemory) 2_000_000L else 4_000_000L,
            standbyBufferBytes = if (lowMemory) 4 * 1024 * 1024 else 8 * 1024 * 1024,
            activeBufferBytes = if (lowMemory) 32 * 1024 * 1024 else TARGET_BUFFER_BYTES,
        )
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory, subtitles.extractorsFactory)
                    // Side-loaded subtitle files go through libass too.
                    .setSubtitleParserFactory(subtitles.parserFactory),
            )
            .setLoadControl(loadControl)
            .build()
        // Buffer the next playlist item while the current item's buffer is healthy. ASS
        // programs use a separate standby instead, because libass owns one item at a time.
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(4_000_000L)
        subtitles.attach(player)
        SubtitleOverlays.register(player, subtitles)
        return PooledPlayer(player, loadControl, trackSelector, subtitles)
    }

    private companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 30_000
        const val TARGET_BUFFER_BYTES = 64 * 1024 * 1024
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
class PoolLoadControl(
    private val base: DefaultLoadControl,
    private val standbyBufferUs: Long = 4_000_000L,
    private val standbyBufferBytes: Int = 8 * 1024 * 1024,
    private val activeBufferBytes: Int = 64 * 1024 * 1024,
) : LoadControl {
    @Volatile var standby: Boolean = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (standby && (parameters.bufferedDurationUs >= standbyBufferUs ||
            base.getAllocator(parameters.playerId).totalBytesAllocated >= standbyBufferBytes)) return false
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
    ): Boolean = !standby && bufferedDurationUs < standbyBufferUs &&
        base.getAllocator(playerId).totalBytesAllocated < activeBufferBytes &&
        base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

}

internal object PlaybackBudget {
    fun isLowMemory(lowRam: Boolean, totalBytes: Long, heapMb: Int): Boolean =
        lowRam || totalBytes <= 2L * 1024 * 1024 * 1024 || heapMb <= 128
}
