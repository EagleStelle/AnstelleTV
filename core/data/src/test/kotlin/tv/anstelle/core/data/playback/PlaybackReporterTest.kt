package tv.anstelle.core.data.playback

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        override suspend fun started(item: PlaystateItem, positionMs: Long) { writes += "start" }
        override suspend fun progress(item: PlaystateItem, positionMs: Long, paused: Boolean) { writes += "session-progress" }
        override suspend fun stopped(item: PlaystateItem, positionMs: Long) { writes += "stop" }
        override suspend fun saveProgress(itemId: String, positionMs: Long, atUtcMs: Long) { writes += "resume:$positionMs" }
        override suspend fun markWatched(itemId: String, atUtcMs: Long) { writes += "watched" }
        override suspend fun isPlayed(itemId: String) = false
        override suspend fun setPlayed(itemId: String, played: Boolean) { writes += "flag:$played" }
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

    private class Fixture(scope: TestScope, sync: WatchSync, monotonic: Boolean = false) {
        var now = 0L
        var elapsed = 0L
        val calls = Calls()
        private val settings = SettingsRepository(object : SettingDao by stub() {
            override suspend fun get(key: String): String? = if (key == Settings.dashboard(1).name) "false" else sync.code.toString()
        })
        val reporter = PlaybackReporter(scope.backgroundScope, settings, { _, _ -> calls },
            DashboardHistory(stub<WatchEventDao>(), settings, TimeSource { now }), TimeSource { now },
            TimeSource { if (monotonic) elapsed else now })
    }

    private fun Fixture.start(onDemand: Boolean, position: Long = 950_000) = reporter.start(
        item, server, WatchAccount("user", "token", null, true), "session", false,
        if (onDemand) null else 7, 1, position, playing = true,
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
        assertEquals(listOf("watched"), f.calls.writes)
    }

    @Test
    fun `on demand status marks natural end with incorrect runtime`() = runTest {
        val f = Fixture(this, WatchSync.STATUS)
        val session = f.start(onDemand = true, position = 0)
        f.now = 60_000
        f.reporter.stop(session, 60_000, ended = true)
        runCurrent()
        assertEquals(listOf("watched"), f.calls.writes)
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
}
