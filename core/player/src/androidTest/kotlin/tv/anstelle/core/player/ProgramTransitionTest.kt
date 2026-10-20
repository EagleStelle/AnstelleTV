package tv.anstelle.core.player

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import tv.anstelle.core.model.TrackPrefs

/** Actual Media3/libass playback with generated, anonymous H.264/AAC samples. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ProgramTransitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private lateinit var server: TransitionSampleServer

    @Before fun serveSamples() { server = TransitionSampleServer(context) }
    @After fun stopServer() { server.close() }

    @Test fun canceledTraceCannotLaterCountAsARenderedTune() {
        val canceled = TuneTrace.begin()
        assertTrue(canceled.end() >= 0)
        assertTrue(canceled.rendered() == -1L)
        val rendered = TuneTrace.begin()
        assertTrue(rendered.rendered() >= 0)
        assertTrue(rendered.end() == -1L)
    }

    @Test fun liveStartsWithQuarterSecondButKeepsRecoveryProtectionAndOnDemandDefaults() {
        instrumentation.runOnMainSync {
            for (mode in PlaybackMode.entries) {
                val pooled = factory().create(mode)
                val id = PlayerId("buffer-test-$mode")
                try {
                    pooled.loadControl.onPrepared(id)
                    val timeline = SinglePeriodTimeline(5_000_000L, true, false, false, null, sample("plain.mp4", "buffer"))
                    fun buffered(us: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
                        id, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0, us, 1f,
                        true, rebuffering, C.TIME_UNSET, C.TIME_UNSET,
                    )
                    assertFalse(pooled.loadControl.shouldStartPlayback(buffered(249_000)))
                    assertTrue(pooled.loadControl.shouldStartPlayback(buffered(250_000)) == (mode == PlaybackMode.LIVE_TV))
                    assertTrue(pooled.loadControl.shouldStartPlayback(buffered(1_000_000)))
                    assertFalse(pooled.loadControl.shouldStartPlayback(buffered(1_999_000, rebuffering = true)))
                    assertTrue(pooled.loadControl.shouldStartPlayback(buffered(2_000_000, rebuffering = true)))
                } finally {
                    pooled.loadControl.onReleased(id)
                    pooled.release()
                }
            }
        }
    }

    @Test fun standbyDefaultsNeverTemporarilyEnableVideoOrUnmute() {
        instrumentation.runOnMainSync {
            val pool = PlayerPool(factory())
            try {
                val standby = pool.standby()
                pool.quiet(standby, concurrentVideoDecoders = false)
                val changes = mutableListOf<TrackSelectionParameters>()
                standby.player.addListener(object : Player.Listener {
                    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) { changes += parameters }
                })
                pool.quiet(standby, concurrentVideoDecoders = false, TrackPrefs(audioLanguages = listOf("ja")))
                assertTrue("Defaults did not reach the selector", changes.isNotEmpty())
                assertTrue(changes.all { C.TRACK_TYPE_VIDEO in it.disabledTrackTypes })
                assertTrue(standby.player.trackSelectionParameters.preferredAudioLanguages == listOf("ja"))
                assertTrue(standby.player.volume == 0f && !standby.player.playWhenReady)
            } finally { pool.release() }
        }
    }

    @Test fun poolCreatesTwoStandbysOnlyWhenNeededSwapsFromEitherAndReleasesThem() {
        instrumentation.runOnMainSync {
            val pool = PlayerPool(factory())
            try {
                val active = pool.active
                assertNull(pool.preparedStandby(0))
                assertNull(pool.preparedStandby(1))
                val first = pool.standby(0)
                val second = pool.standby(1)
                assertTrue(pool.preparedStandby(0) === first && pool.preparedStandby(1) === second && first !== second)
                pool.quiet(second, concurrentVideoDecoders = true)
                assertTrue("Only the first standby may decode video", C.TRACK_TYPE_VIDEO in second.player.trackSelectionParameters.disabledTrackTypes)

                // Either slot swaps; the other stays, and the old active player takes the slot's place, stopped.
                for ((slot, next) in listOf(1 to second, 0 to first)) {
                    val previous = pool.active
                    previous.player.setMediaItem(sample("plain.mp4", "left"))
                    previous.channelId = 1L
                    assertTrue(pool.swap(slot) === next && pool.active === next)
                    assertTrue(pool.preparedStandby(slot) === previous && pool.preparedStandby(1 - slot) !== next)
                    assertTrue(previous.player.mediaItemCount == 0 && previous.channelId == null)
                    assertTrue(previous.loadControl.standby && !next.loadControl.standby)
                }
                assertTrue(pool.preparedStandby(0) === second && pool.preparedStandby(1) === active)

                pool.releaseStandby(1)
                assertNull(pool.preparedStandby(1))
                assertTrue(pool.preparedStandby(0) === second)
                pool.standby(1)
                pool.stopAll()
                assertNull(pool.preparedStandby(0))
                assertNull(pool.preparedStandby(1))
                assertTrue(pool.active === first)
            } finally { pool.release() }
        }
    }

    @Test fun standbyFollowsTheLivePointAndSwapsInsideItsBuffer() {
        val pool = onMain { PlayerPool(factory()) }
        var loads = 0
        try {
            val standby = onMain {
                pool.standby().also { standby ->
                    pool.quiet(standby, concurrentVideoDecoders = false)
                    standby.player.addAnalyticsListener(object : AnalyticsListener {
                        override fun onLoadStarted(time: AnalyticsListener.EventTime, load: LoadEventInfo, media: MediaLoadData, retryCount: Int) { loads++ }
                    })
                    // One keyframe for all 6 s: a buffer that lost it can only seek by loading again.
                    standby.player.setMediaItem(sample("plain.mp4", "standby"))
                    standby.player.prepare()
                }
            }
            fun settle(pooled: PooledPlayer) = assertTrue("Player never settled", awaitMain { pooled.player.playbackState == Player.STATE_READY && !pooled.player.isLoading })
            settle(standby)
            val loaded = onMain { loads }
            val half = standby.loadControl.standbyBufferUs / 2_000
            for (live in listOf(half, 2 * half)) {
                onMain { pool.followLive(standby, live) }
                settle(standby)
            }
            val active = onMain {
                standby.player.seekTo(0, 2 * half + 1)
                pool.swap().also { it.resetTrackParameters(TrackPrefs()) }
            }
            settle(active)
            assertEquals("Following the live point or swapping loaded the media again", loaded, onMain { loads })
            assertEquals("The swap left its keyframe", 0L, onMain { active.player.currentPosition })
            assertEquals(SeekParameters.DEFAULT, onMain { active.player.seekParameters })
        } finally {
            onMain { pool.release() }
        }
    }

    @Test fun directPlayKeepsTheFileFontsThenSkipsThemUnread() {
        val fonts = FontCache(context, OkHttpClient(), StreamAuth(), Dispatchers.IO)
        // fonts.mkv is styled.mkv with a 512 KiB attachment, Example.ttf, ahead of its first cluster.
        val stream = ProgramStream(
            "${server.baseUrl}/fonts.mkv", mimeType = null, transcoded = false, playSessionId = "fonts",
            fonts = listOf(FontRequest("${server.baseUrl}/attachments/0", "Example.ttf")), hasAss = true,
        )
        assertTrue("A first play has nothing kept", runBlocking { fonts.load(stream) }.isEmpty())
        val read = bytesLoaded(stream, fonts)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var kept = runBlocking { fonts.load(stream) }
        while (kept.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            kept = runBlocking { fonts.load(stream) }
        }
        assertEquals("The file's font was not kept", ATTACHMENT_BYTES, kept.singleOrNull()?.data?.size)
        val skipped = bytesLoaded(stream, fonts)
        Log.i("Anstelle/TransitionTest", "Attachments read: $read bytes loaded; skipped: $skipped")
        assertTrue("The first play skipped the fonts: $read bytes", read >= ATTACHMENT_BYTES)
        assertTrue("The fonts were read again: $skipped bytes", skipped < ATTACHMENT_BYTES)
    }

    /** Plays [stream] directly with the fonts [fonts] gives it, and returns the bytes its whole file took to load. */
    private fun bytesLoaded(stream: ProgramStream, fonts: FontCache): Long {
        val given = runBlocking { fonts.load(stream) }
        val loaded = CountDownLatch(1)
        var bytes = -1L
        var error: PlaybackException? = null
        val pooled = onMain {
            PlayerFactory(context, OkHttpClient(), StreamAuth(), fonts).create().also { pooled ->
                pooled.player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onLoadCompleted(time: AnalyticsListener.EventTime, load: LoadEventInfo, media: MediaLoadData) {
                        if (media.dataType == C.DATA_TYPE_MEDIA) bytes = load.bytesLoaded.also { loaded.countDown() }
                    }
                    override fun onPlayerError(time: AnalyticsListener.EventTime, e: PlaybackException) { error = e; loaded.countDown() }
                })
                pooled.player.setMediaItem(stream.mediaItem("fonts"))
                pooled.subtitles.addFonts(given, stream)
                pooled.player.prepare()
            }
        }
        try {
            assertTrue("The file never finished loading", loaded.await(10, TimeUnit.SECONDS))
            assertNull(error?.cause?.toString(), error)
            assertTrue("Never ready", awaitMain { pooled.player.playbackState == Player.STATE_READY })
            assertTrue("The subtitle track is missing", onMain { pooled.player.currentTracks.isTypeSupported(C.TRACK_TYPE_TEXT) })
        } finally {
            onMain { pooled.release() }
        }
        return bytes
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun awaitMain(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return true
            SystemClock.sleep(50)
        }
        return false
    }

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
            val standby = players.standby()
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

    private companion object {
        const val ATTACHMENT_BYTES = 512 * 1024
    }

    private fun factory() =PlayerFactory(context, OkHttpClient(), StreamAuth(), FontCache(context, OkHttpClient(), StreamAuth(), Dispatchers.IO))
    private fun sample(file: String, id: String) = MediaItem.Builder()
        .setUri("${server.baseUrl}/$file").setMediaId(id).build()
}
