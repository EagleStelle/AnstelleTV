package tv.anstelle.core.data.playback

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import tv.anstelle.core.model.WatchSource
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WatchAccount
import tv.anstelle.core.data.stats.DashboardHistory
import tv.anstelle.core.data.stub
import tv.anstelle.core.database.dao.SettingDao
import tv.anstelle.core.database.dao.WatchEventDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.jellyfin.Playstate
import tv.anstelle.core.jellyfin.PlaystateItem
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.WatchSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackReporterTest {
    private class Calls : Playstate by stub() {
        val writes = ArrayList<String>()
        val presence = ArrayList<String>()
        var failure: Exception? = null
        var flagFailure: Exception? = null
        var block: CompletableDeferred<Unit>? = null
        override suspend fun started(item: PlaystateItem, positionMs: Long) {
            block?.await()
            if (item.presence == null) writes += "start" else presence += "start:${item.presence?.id}"
        }
        override suspend fun progress(item: PlaystateItem, positionMs: Long, paused: Boolean) {
            if (item.presence == null) writes += "session-progress" else presence += "progress:$positionMs:$paused"
        }
        override suspend fun stopped(item: PlaystateItem, positionMs: Long) {
            if (item.presence == null) writes += "stop" else presence += "stop:$positionMs"
        }
        override suspend fun saveProgress(itemId: String, positionMs: Long, atUtcMs: Long) {
            failure?.let { failure = null; throw it }
            writes += "resume:$positionMs"
        }
        override suspend fun markWatched(itemId: String, atUtcMs: Long) { writes += "watched" }
        override suspend fun isPlayed(itemId: String) = false
        override suspend fun setPlayed(itemId: String, played: Boolean) {
            flagFailure?.let { flagFailure = null; throw it }
            writes += "flag:$played"
        }
    }

    private val item = MediaItemEntity(
        id = 1, serverId = 1, libraryId = null, remoteId = "item", type = MediaType.EPISODE,
        title = "Episode", sortTitle = null, seriesRemoteId = "series", showTitle = "Series",
        season = 1, episode = 1, durationMs = 1_000_000, genres = "", studios = "", tags = "",
        year = null, premiereDateUtcMs = null, officialRating = null, ratingScore = null,
        overview = null, communityRating = null, hdrType = HdrType.SDR, audioFormat = null,
        videoCodec = null, audioCodec = null, container = null, width = null, height = null,
        videoBitDepth = null, audioChannels = null, mediaSourceId = null, tracks = MediaTracks(),
        audioLanguages = "", primaryImageTag = null, backdropImageTag = null, logoImageTag = null,
        updatedAtUtcMs = 0,
    )
    private val server = MediaServerEntity(1, "server", "Server", "http://jellyfin.test", "user", "User", null, 0)

    private class Fixture(scope: TestScope, sync: WatchSync, monotonic: Boolean = false,
        val demandSync: WatchSync = sync) {
        var now = 0L
        var elapsed = 0L
        val calls = Calls()
        private val settings = SettingsRepository(object : SettingDao by stub() {
            override suspend fun get(key: String): String? = when (key) {
                Settings.dashboard(1).name -> "false"
                Settings.onDemandSync(1).name -> demandSync.code.toString()
                else -> sync.code.toString()
            }
        })
        val reconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var authenticated = true
        var signedOut = false
        val reporter = PlaybackReporter(scope.backgroundScope, settings, { _, _ -> calls },
            DashboardHistory(stub<WatchEventDao>(), settings, TimeSource { now }), TimeSource { now },
            TimeSource { if (monotonic) elapsed else now },
            { if (authenticated) it.account else null }, { signedOut = true; authenticated = false }, reconnects)
    }

    private fun Fixture.start(onDemand: Boolean, position: Long = 950_000) = reporter.start(
        item, server, WatchAccount("user", "token", null, true), "session", false,
        if (onDemand) null else 7, 1, position, playing = true,
        source = if (onDemand) WatchSource.ON_DEMAND else WatchSource.CHANNEL,
    )

    @Test
    fun `progress writes only resume data even when playback finishes`() = runTest {
        for (onDemand in listOf(false, true)) {
            val f = Fixture(this, WatchSync.PROGRESS)
            val session = f.start(onDemand)
            f.now = 30_000
            f.reporter.playing(session, 980_000)
            runCurrent()
            f.reporter.progress(session, 990_000, false)
            f.now = 40_000
            f.reporter.stop(session, 1_000_000, ended = true)
            runCurrent()
            assertTrue(f.calls.writes.isNotEmpty())
            assertTrue(f.calls.writes.all { it.startsWith("resume:") }, f.calls.writes.toString())
            assertEquals("resume:1000000", f.calls.writes.last())
            assertTrue(f.calls.presence.first().startsWith("start:"))
            assertEquals("stop:1000000", f.calls.presence.last())
        }
    }

    @Test
    fun `late live tv hop under thirty seconds sends nothing in any mode`() = runTest {
        for (sync in WatchSync.entries) {
            val f = Fixture(this, sync)
            val session = f.start(onDemand = false)
            f.now = 29_999
            f.reporter.playing(session, 999_999)
            f.reporter.stop(session, 1_000_000, ended = true)
            runCurrent()
            assertTrue(f.calls.writes.isEmpty(), "$sync: ${f.calls.writes}")
        }
    }

    @Test
    fun `status marks a late live tv finish after exactly thirty seconds`() = runTest {
        val f = Fixture(this, WatchSync.STATUS)
        val session = f.start(onDemand = false)
        f.now = 30_000
        f.reporter.stop(session, 980_000)
        runCurrent()
        assertEquals(listOf("flag:true"), f.calls.writes)
    }

    @Test
    fun `on demand status marks natural end with incorrect runtime`() = runTest {
        val f = Fixture(this, WatchSync.STATUS)
        val session = f.start(onDemand = true, position = 0)
        f.now = 60_000
        f.reporter.stop(session, 60_000, ended = true)
        runCurrent()
        assertEquals(listOf("flag:true"), f.calls.writes)
    }

    @Test
    fun `buffering cannot qualify a late live tv view`() = runTest {
        val f = Fixture(this, WatchSync.PROGRESS)
        val session = f.start(onDemand = false)
        f.now = 10_000
        f.reporter.playingChanged(session, false)
        f.now = 120_000
        f.reporter.playing(session, 980_000)
        f.reporter.stop(session, 1_000_000, ended = true)
        runCurrent()
        assertTrue(f.calls.writes.isEmpty())
    }

    @Test
    fun `wall clock jump cannot qualify a channel hop`() = runTest {
        for (sync in WatchSync.entries) {
            val f = Fixture(this, sync, monotonic = true)
            val session = f.start(onDemand = false)
            f.now = 3_600_000
            f.elapsed = 29_999
            f.reporter.playing(session, 999_999)
            f.reporter.stop(session, 1_000_000, ended = true)
            runCurrent()
            assertTrue(f.calls.writes.isEmpty(), "$sync: ${f.calls.writes}")
        }
    }

    @Test
    fun `on demand uses its own mode after leaving live tv`() = runTest {
        for ((live, demand) in listOf(WatchSync.BOTH to WatchSync.OFF, WatchSync.OFF to WatchSync.PROGRESS)) {
            val f = Fixture(this, live, demandSync = demand)
            val channel = f.start(false, position = 0)
            f.now = 35_000
            f.reporter.stop(channel, 35_000)
            runCurrent()
            f.calls.writes.clear()
            val session = f.start(true, position = 35_000)
            assertEquals(WatchSource.ON_DEMAND, session.source)
            f.reporter.progress(session, 50_000, false)
            f.reporter.stop(session, 50_000)
            runCurrent()
            if (demand == WatchSync.OFF) assertTrue(f.calls.writes.isEmpty())
            else assertEquals(listOf("resume:50000"), f.calls.writes)
        }
    }

    @Test
    fun `active player source overrides carried navigation channel`() = runTest {
        val f = Fixture(this, WatchSync.OFF, demandSync = WatchSync.PROGRESS)
        val session = f.reporter.start(item, server, WatchAccount("user", "token", null, true),
            "demand", false, channelId = 7, profileId = 1, positionMs = 10_000,
            playing = true, source = WatchSource.ON_DEMAND)
        runCurrent()
        assertEquals(listOf("resume:10000"), f.calls.writes)
    }

    @Test
    fun `all modes keep independent progress status and presence policies in both players`() = runTest {
        for (onDemand in listOf(false, true)) for (mode in WatchSync.entries) {
            val f = Fixture(this, mode)
            val session = f.start(onDemand, 0)
            runCurrent()
            f.now = 30_000
            f.reporter.playing(session, 500_000)
            runCurrent()
            f.reporter.stop(session, 1_000_000, ended = true)
            runCurrent()
            when (mode) {
                WatchSync.OFF -> { assertTrue(f.calls.writes.isEmpty()); assertTrue(f.calls.presence.isEmpty()) }
                WatchSync.STATUS -> { assertEquals(listOf("flag:true"), f.calls.writes); assertTrue(f.calls.presence.isEmpty()) }
                WatchSync.PROGRESS -> { assertTrue(f.calls.writes.all { it.startsWith("resume:") }); assertTrue(f.calls.presence.isNotEmpty()) }
                WatchSync.BOTH -> { assertTrue("start" in f.calls.writes); assertTrue("stop" in f.calls.writes); assertTrue("flag:true" in f.calls.writes) }
            }
        }
    }

    @Test
    fun `queued completion survives a seek back before network reporting`() = runTest {
        val f = Fixture(this, WatchSync.STATUS)
        val session = f.start(true, 0)
        f.reporter.progress(session, 990_000, false)
        f.reporter.progress(session, 0, false)
        runCurrent()
        assertEquals(listOf("flag:true"), f.calls.writes)
    }

    @Test
    fun `completion survives an unauthorized write followed by a seek back`() = runTest {
        val f = Fixture(this, WatchSync.STATUS)
        val session = f.start(true, 0)
        f.calls.flagFailure = ServerConnectException(ServerProblem.SIGNED_OUT, "Expired")
        f.reporter.progress(session, 990_000, false)
        runCurrent()
        f.authenticated = true
        f.reporter.progress(session, 0, false)
        runCurrent()
        assertEquals(listOf("flag:true"), f.calls.writes)
    }

    @Test
    fun `both mode never restores an unplayed flag after qualifying completion`() = runTest {
        val f = Fixture(this, WatchSync.BOTH)
        val session = f.start(false, 0)
        f.now = 30_000
        f.reporter.playing(session, 990_000)
        runCurrent()
        f.reporter.stop(session, 10_000)
        runCurrent()
        assertEquals(listOf("flag:true"), f.calls.writes.filter { it.startsWith("flag:") })
    }

    @Test
    fun `progress reports pauses and coalesces queued positions`() = runTest {
        val f = Fixture(this, WatchSync.PROGRESS)
        val release = CompletableDeferred<Unit>()
        f.calls.block = release
        val session = f.start(true, 0)
        runCurrent()
        repeat(100) { f.reporter.progress(session, it * 1_000L, false) }
        f.reporter.progress(session, 100_000, true)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("resume:0", "resume:100000"), f.calls.writes)
        assertEquals("progress:100000:true", f.calls.presence.last())
        f.reporter.stop(session, 101_000)
        runCurrent()
        assertEquals("stop:101000", f.calls.presence.last())
    }

    @Test
    fun `status marks completion once while leaving progress untouched`() = runTest {
        val f = Fixture(this, WatchSync.STATUS)
        val session = f.start(true, 0)
        f.reporter.progress(session, 950_000, false)
        runCurrent()
        f.reporter.progress(session, 960_000, false)
        f.reporter.stop(session, 1_000_000, ended = true)
        runCurrent()
        assertEquals(listOf("flag:true"), f.calls.writes)
        assertTrue(f.calls.presence.isEmpty())
    }

    @Test
    fun `transient progress failures retry without duplicating the start`() = runTest {
        val f = Fixture(this, WatchSync.PROGRESS)
        f.calls.failure = ServerConnectException(ServerProblem.UNREACHABLE, "offline")
        f.start(true, 20_000)
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        assertEquals(listOf("resume:20000"), f.calls.writes)
        assertEquals(1, f.calls.presence.count { it.startsWith("start:") })
    }

    @Test
    fun `unauthorized reports emit failure and resume after reconnect`() = runTest {
        val f = Fixture(this, WatchSync.PROGRESS)
        val successes = ArrayList<Long>()
        val failures = ArrayList<HistoryFailure>()
        backgroundScope.launch { f.reporter.reported.collect { successes += it } }
        backgroundScope.launch { f.reporter.failures.collect { failures += it } }
        runCurrent()
        f.calls.failure = ServerConnectException(ServerProblem.SIGNED_OUT, "expired")
        val session = f.start(true, 10_000)
        f.reporter.stop(session, 20_000)
        runCurrent()
        assertTrue(f.signedOut)
        assertTrue(successes.isEmpty())
        assertTrue(failures.single().reconnect)
        f.authenticated = true
        f.reconnects.tryEmit(Unit)
        runCurrent()
        assertEquals(listOf(1L), successes)
        assertEquals("resume:20000", f.calls.writes.last())
    }

    @Test
    fun `a delayed live tv stop cannot overwrite the newer on demand view after reconnect`() = runTest {
        val f = Fixture(this, WatchSync.PROGRESS)
        val channel = f.start(false, 0)
        f.now = 35_000
        f.calls.failure = ServerConnectException(ServerProblem.SIGNED_OUT, "expired")
        f.reporter.stop(channel, 35_000)
        runCurrent()
        f.authenticated = true
        val demand = f.start(true, 50_000)
        runCurrent()
        f.reconnects.tryEmit(Unit)
        runCurrent()
        assertEquals("resume:50000", f.calls.writes.last())
        assertTrue(f.calls.presence.none { it == "stop:35000" })
        f.reporter.stop(demand, 55_000)
        runCurrent()
        assertEquals("stop:55000", f.calls.presence.last())
    }

    @Test
    fun `reconnect preserves an earlier status completion without rewinding newer progress`() = runTest {
        val f = Fixture(this, WatchSync.STATUS, demandSync = WatchSync.PROGRESS)
        val channel = f.start(false, 950_000)
        f.now = 35_000
        f.calls.flagFailure = ServerConnectException(ServerProblem.SIGNED_OUT, "expired")
        f.reporter.stop(channel, 1_000_000, ended = true)
        runCurrent()
        f.authenticated = true
        f.start(true, 50_000)
        runCurrent()
        f.reconnects.tryEmit(Unit)
        runCurrent()
        assertEquals(listOf("resume:50000", "flag:true"), f.calls.writes)
        assertTrue(f.calls.presence.none { it.startsWith("stop:") })
    }
}
