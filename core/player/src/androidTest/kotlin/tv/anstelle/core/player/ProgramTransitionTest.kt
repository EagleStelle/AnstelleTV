package tv.anstelle.core.player

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Media3/libass playback with generated, anonymous H.264/AAC samples. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ProgramTransitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private lateinit var server: TransitionSampleServer

    @Before fun serveSamples() { server = TransitionSampleServer(context) }
    @After fun stopServer() { server.close() }

    @Test
    fun playlistPreloadsNextProgramWithoutRebuffering() {
        val done = CountDownLatch(1)
        var pooled: PooledPlayer? = null
        var surface: Surface? = null
        var error: PlaybackException? = null
        var next = false
        var rebuffers = 0
        instrumentation.runOnMainSync {
            surface = PlaceholderSurface.newInstance(context, false)
            pooled = factory().create().also { p ->
                p.player.setVideoSurface(surface)
                p.player.volume = 0f
                p.player.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) next = true
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (next && state == Player.STATE_BUFFERING) rebuffers++
                        if (state == Player.STATE_ENDED) done.countDown()
                    }
                    override fun onPlayerError(e: PlaybackException) { error = e; done.countDown() }
                })
                p.player.setMediaItems(listOf(sample("plain.mp4", "first"), sample("plain.mp4", "second")))
                p.player.prepare()
                p.player.play()
            }
        }
        try {
            assertTrue("Playlist timed out", done.await(25, TimeUnit.SECONDS))
            assertNull(error?.cause?.toString(), error)
            assertTrue("Next item never played", next)
            assertTrue("Rebuffered $rebuffers times at the boundary", rebuffers == 0)
        } finally {
            instrumentation.runOnMainSync { pooled?.release(); surface?.release() }
        }
    }

    @Test
    fun styledProgramIsPreparedAndSwappedWithOneVideoDecoder() {
        val done = CountDownLatch(1)
        var pool: PlayerPool? = null
        var surface: Surface? = null
        var error: PlaybackException? = null
        var boundaryAt = 0L
        var firstFrameAt = 0L
        var nextBuffered = 0L
        instrumentation.runOnMainSync {
            val players = PlayerPool(factory()).also { pool = it }
            surface = PlaceholderSurface.newInstance(context, false)
            val standby = players.standby
            players.quiet(standby, concurrentVideoDecoders = false)
            standby.player.setMediaItem(sample("styled.mkv", "next"))
            standby.player.prepare()
            standby.player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (boundaryAt != 0L) firstFrameAt = SystemClock.elapsedRealtime()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) done.countDown()
                }
                override fun onPlayerError(e: PlaybackException) { error = e; done.countDown() }
            })
            val active = players.active
            active.player.setVideoSurface(surface)
            active.player.volume = 0f
            active.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state != Player.STATE_ENDED) return
                    boundaryAt = SystemClock.elapsedRealtime()
                    nextBuffered = standby.player.bufferedPosition
                    val next = players.swap()
                    next.player.trackSelectionParameters = next.player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                    next.player.setVideoSurface(surface)
                    next.player.play()
                }
                override fun onPlayerError(e: PlaybackException) { error = e; done.countDown() }
            })
            active.player.setMediaItem(sample("styled.mkv", "current"))
            active.player.prepare()
            active.player.play()
        }
        try {
            assertTrue("Standby hand-off timed out", done.await(25, TimeUnit.SECONDS))
            assertNull(error?.cause?.toString(), error)
            assertTrue("No next-program buffer: $nextBuffered ms", nextBuffered >= 1_000)
            assertTrue("No next-program video frame", firstFrameAt >= boundaryAt && firstFrameAt > 0)
            val gap = firstFrameAt - boundaryAt
            Log.i("Anstelle/TransitionTest", "Single-decoder ASS hand-off: $gap ms; $nextBuffered ms buffered")
            assertTrue("Decoder hand-off took $gap ms", gap < 1_000)
        } finally {
            instrumentation.runOnMainSync { pool?.release(); surface?.release() }
        }
    }

    private fun factory() = PlayerFactory(context, OkHttpClient(), StreamAuth())
    private fun sample(file: String, id: String) = MediaItem.Builder()
        .setUri("${server.baseUrl}/$file").setMediaId(id).build()
}
