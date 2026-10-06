package tv.anstelle.core.player

import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The FFmpeg extension is built for an older Media3 than the app uses. This checks on a device
 * that it still loads in the player and decodes the surround formats it is there for.
 * Samples are one-second stereo tones made with the ffmpeg command line.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class FfmpegDecoderTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun extensionLoadsIntoThePlayersRenderers() {
        assertTrue("FFmpeg library not available", FfmpegLibrary.isAvailable())
        val handler = Handler(InstrumentationRegistry.getInstrumentation().targetContext.mainLooper)
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .createRenderers(handler, NoVideo, NoAudio, { }, { })
        assertTrue("no FFmpeg audio renderer among ${renderers.map { it.name }}", renderers.any { it is FfmpegAudioRenderer })
    }

    @Test
    fun decodesAc3() = assertDecodes("ac3")

    @Test
    fun decodesEac3() = assertDecodes("eac3")

    @Test
    fun decodesDts() = assertDecodes("dts")

    @Test
    fun decodesTrueHd() = assertDecodes("truehd")

    /** Plays the sample to its end with only the FFmpeg renderer and checks FFmpeg decoded it. */
    private fun assertDecodes(name: String) {
        val thread = HandlerThread("ffmpeg-$name").apply { start() }
        val done = CountDownLatch(1)
        var decoder: String? = null
        var error: PlaybackException? = null
        var player: ExoPlayer? = null
        Handler(thread.looper).post {
            val ffmpegOnly = RenderersFactory { eventHandler, _, audioListener, _, _ ->
                arrayOf(FfmpegAudioRenderer(eventHandler, audioListener, DefaultAudioSink.Builder(context).build()))
            }
            player = ExoPlayer.Builder(context, ffmpegOnly).setLooper(thread.looper).build().apply {
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        decoder = decoderName
                    }
                })
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) done.countDown()
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        error = e
                        done.countDown()
                    }
                })
                setMediaItem(MediaItem.fromUri("asset:///ffmpeg/$name.mka"))
                prepare()
                play()
            }
        }
        try {
            assertTrue("$name did not finish in time", done.await(TIMEOUT_S, TimeUnit.SECONDS))
            assertNull("$name failed: ${error?.errorCodeName}", error)
            assertTrue("$name decoded by $decoder", decoder.orEmpty().contains("ffmpeg", ignoreCase = true))
        } finally {
            Handler(thread.looper).post { player?.release() }
            thread.quitSafely()
        }
    }

    private companion object {
        const val TIMEOUT_S = 20L
        val NoVideo = object : androidx.media3.exoplayer.video.VideoRendererEventListener {}
        val NoAudio = object : androidx.media3.exoplayer.audio.AudioRendererEventListener {}
    }
}
