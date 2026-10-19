package tv.anstelle.core.player

import android.os.Debug
import android.os.Process
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
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Fixed files, positions and one video decoder; also runs unchanged against the previous player pool. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ChannelTuneBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context

    @Test fun matchedColdAndWarmTunes() {
        val cpu = Process.getElapsedCpuTime()
        var peakPssKb = 0L
        var rebuffers = 0
        TransitionSampleServer(context).use { server ->
            for (file in listOf("plain.mp4", "styled.mkv")) {
                for (warm in listOf(false, true)) repeat(6) { iteration ->
                    var pool: PlayerPool? = null
                    var surface: Surface? = null
                    val ready = CountDownLatch(1)
                    val frame = CountDownLatch(1)
                    val error = AtomicReference<PlaybackException>()
                    val started = AtomicLong()
                    val elapsed = AtomicLong(-1)
                    val makeItem = { id: String -> MediaItem.Builder().setUri("${server.baseUrl}/$file").setMediaId(id).build() }
                    try {
                        instrumentation.runOnMainSync {
                            val players = PlayerPool(PlayerFactory(context, OkHttpClient(), StreamAuth())).also { pool = it }
                            surface = PlaceholderSurface.newInstance(context, false)
                            val target = if (warm) players.standby.also {
                                players.quiet(it, concurrentVideoDecoders = false)
                            } else players.active
                            target.player.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state == Player.STATE_READY) ready.countDown()
                                    if (elapsed.get() >= 0 && state == Player.STATE_BUFFERING) rebuffers++
                                }
                                override fun onRenderedFirstFrame() {
                                    if (elapsed.compareAndSet(-1, SystemClock.elapsedRealtime() - started.get())) frame.countDown()
                                }
                                override fun onPlayerError(e: PlaybackException) {
                                    error.set(e); ready.countDown(); frame.countDown()
                                }
                            })
                            if (!warm) {
                                target.player.setVideoSurface(surface)
                                target.player.volume = 0f
                                started.set(SystemClock.elapsedRealtime())
                            }
                            target.player.setMediaItems(listOf(makeItem("target")), 0, 1_500)
                            target.player.prepare()
                            if (!warm) target.player.play()
                        }
                        if (warm) {
                            assertTrue("Standby did not prepare", ready.await(15, TimeUnit.SECONDS))
                            assertNull(error.get())
                            instrumentation.runOnMainSync {
                                started.set(SystemClock.elapsedRealtime())
                                val target = pool!!.swap()
                                target.player.trackSelectionParameters = target.player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                                target.player.seekTo(0, 1_500)
                                target.player.setVideoSurface(surface)
                                target.player.play()
                            }
                        }
                        assertTrue("First frame timed out", frame.await(15, TimeUnit.SECONDS))
                        assertNull(error.get())
                        assertTrue("First frame missing", elapsed.get() >= 0)
                        Thread.sleep(500)
                        peakPssKb = maxOf(peakPssKb, Debug.getPss())
                        Log.i("Anstelle/TuneSample", "$file ${if (warm) "warm" else "cold"} $iteration ${elapsed.get()} ms")
                    } finally {
                        instrumentation.runOnMainSync { pool?.release(); surface?.release() }
                    }
                }
            }
        }
        Log.i("Anstelle/TuneSample", "CPU ${Process.getElapsedCpuTime() - cpu} ms; peak PSS $peakPssKb KB; rebuffers $rebuffers")
        assertTrue("Rebuffered after first frame: $rebuffers", rebuffers == 0)
    }
}
