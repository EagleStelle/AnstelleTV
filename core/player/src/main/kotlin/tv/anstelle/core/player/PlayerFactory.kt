package tv.anstelle.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Buffer sizing for the two players in the pool. */
enum class BufferProfile {
    /** The player on screen. */
    ACTIVE,

    /** The pre-warmed player for the next likely channel: small buffer, low memory. */
    PREWARM,
}

/**
 * Builds ExoPlayer instances for the player pool (Phase 5).
 *
 * All media requests go through the app's shared [OkHttpClient]. Platform decoders are
 * preferred; the FFmpeg audio renderer is only used when the device cannot decode a
 * format itself (e.g. TrueHD or DTS on a phone).
 */
@Singleton
@OptIn(UnstableApi::class)
class PlayerFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
) {
    private val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

    fun create(profile: BufferProfile): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = when (profile) {
            BufferProfile.ACTIVE -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
                .build()
            BufferProfile.PREWARM -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_000, 4_000, 1_000, 1_000)
                .build()
        }
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }
}
