package tv.anstelle.app

import android.view.Surface
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.data.playback.ChannelPlayer
import tv.anstelle.core.data.playback.TuneStatus
import java.util.concurrent.atomic.AtomicLong

/** Opt-in test against the configured library; injects delayed callbacks from a retired attachment. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class LiveTvSwitchTest {
    @Test fun rapidSwitchesAndStopIgnoreRetiredCallbacks() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLiveTv") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = EntryPointAccessors.fromApplication(instrumentation.targetContext, LiveTvTestEntryPoint::class.java)
        val profile = graph.profiles().activeProfile()
        val channels = graph.profiles().tunable(graph.channelRepository().observeChannels().first(), graph.profiles().access(profile.id)).take(3)
        assumeTrue("Requires three configured channels", channels.size == 3)
        val playback = graph.playback()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var surface: Surface? = null
        val frameChannel = AtomicLong(-1)
        val listenerField = ChannelPlayer::class.java.getDeclaredField("listener").apply { isAccessible = true }
        try {
            instrumentation.runOnMainSync {
                surface = PlaceholderSurface.newInstance(instrumentation.targetContext, false)
                playback.stop()
                playback.setLineup(channels)
                playback.acquire()
                scope.launch {
                    playback.player.collect { player ->
                        player?.setVideoSurface(surface)
                        player?.addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { frameChannel.set(playback.state.value.channel?.id ?: -1) }
                        })
                    }
                }
                playback.tune(channels[0].id)
            }
            suspend fun frameOn(id: Long) = withTimeout(30_000) {
                while (frameChannel.get() != id || playback.state.value.status != TuneStatus.PLAYING) delay(50)
            }
            frameOn(channels[0].id)
            instrumentation.runOnMainSync {
                val retired = listenerField.get(playback) as Player.Listener
                playback.channelUp()
                playback.channelUp()
                playback.channelDown()
                val requested = playback.state.value
                retired.onPlaybackStateChanged(Player.STATE_ENDED)
                retired.onPlayerError(PlaybackException("Injected obsolete error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                retired.onRenderedFirstFrame()
                assertEquals(requested, playback.state.value)
            }
            frameOn(channels[1].id)
            if (playback.state.value.stream?.transcoding == false) {
                instrumentation.runOnMainSync {
                    frameChannel.set(-1)
                    (listenerField.get(playback) as Player.Listener).onPlayerError(
                        PlaybackException("Injected active format error", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
                    )
                }
                withTimeout(30_000) {
                    while (playback.state.value.stream?.transcoding != true || frameChannel.get() != channels[1].id) delay(50)
                }
                Log.i("Anstelle/LiveTvSwitchTest", "Active format error recovered over HLS")
            }
            instrumentation.runOnMainSync {
                assertNull(playback.state.value.message)
                val retired = listenerField.get(playback) as Player.Listener
                playback.stop()
                assertNotSame(retired, listenerField.get(playback))
                retired.onRenderedFirstFrame()
                retired.onIsPlayingChanged(true)
                retired.onPlayerError(PlaybackException("Injected stopped error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                assertEquals(TuneStatus.IDLE, playback.state.value.status)
                assertNull(playback.state.value.channel)
            }
        } finally {
            instrumentation.runOnMainSync { scope.cancel(); playback.release(); playback.stop(); surface?.release() }
        }
    }
}
