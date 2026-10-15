package tv.anstelle.core.data.playback

import kotlinx.coroutines.test.runTest
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.stub
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaRuntimeRow
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.SettingDao
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.SettingEntity
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.jellyfin.sync.FileDurationProbe
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaDurationsTest {
    private val minute = 60_000L
    private val normal = 24 * minute

    private fun row(id: Long, duration: Long = normal, season: Int = 1, series: String = "series", server: Long = 1) =
        MediaRuntimeRow(id, server, "item-$id", "source-$id", series, season, id.toInt(), duration)

    private fun entity(row: MediaRuntimeRow) = MediaItemEntity(
        id = row.id, serverId = row.serverId, libraryId = null, remoteId = row.remoteId, type = MediaType.EPISODE,
        title = "Episode", sortTitle = null, seriesRemoteId = row.seriesRemoteId, showTitle = "Series",
        season = row.season, episode = row.episode, durationMs = row.durationMs, genres = "", studios = "", tags = "",
        year = null, premiereDateUtcMs = null, officialRating = null, ratingScore = null, overview = null,
        communityRating = null, hdrType = HdrType.SDR, audioFormat = null, videoCodec = null, audioCodec = null,
        container = null, width = null, height = null, videoBitDepth = null, audioChannels = null,
        mediaSourceId = row.mediaSourceId, tracks = MediaTracks(), audioLanguages = "", primaryImageTag = null,
        backdropImageTag = null, logoImageTag = null, updatedAtUtcMs = 0,
    )

    private inner class Fixture(reported: Long, var measured: Long? = normal) {
        val rows = (1L..5).map { row(it, if (it == 3L) reported else normal) }.associateByTo(LinkedHashMap()) { it.id }
        val probes = ArrayList<String>()
        val stored = HashMap<String, String>()
        private val items = object : MediaItemDao by stub() {
            override suspend fun runtimes(seriesType: MediaType) = rows.values.toList()
            override suspend fun setDuration(id: Long, durationMs: Long) { rows[id] = rows.getValue(id).copy(durationMs = durationMs) }
            override suspend fun upsertAll(items: List<MediaItemEntity>) {
                for (item in items) rows[item.id] = MediaRuntimeRow(item.id, item.serverId, item.remoteId,
                    item.mediaSourceId, item.seriesRemoteId, item.season, item.episode, item.durationMs)
            }
        }
        private val servers = object : MediaServerDao by stub() {
            override suspend fun byId(id: Long) = MediaServerEntity(id, "server", "Server", "http://jellyfin.test", "user", "User", null, 0)
        }
        private val tokens = object : TokenStore by stub() {
            override fun get(jellyfinServerId: String) = "token"
        }
        private val settings = SettingsRepository(object : SettingDao by stub() {
            override suspend fun get(key: String) = stored[key]
            override suspend fun put(setting: SettingEntity) { stored[setting.key] = setting.value }
        })
        private val files = object : FileDurationProbe {
            override suspend fun durationMs(baseUrl: String, token: String, itemId: String, sourceId: String?): Long? {
                probes += itemId
                return measured
            }
        }
        val durations = MediaDurations(items, servers, tokens, settings, files)
    }

    @Test
    fun `both inflated and understated runtimes are measured before scheduling`() = runTest {
        for (bad in listOf(normal * 10, normal / 10)) {
            val fixture = Fixture(bad)
            fixture.durations.refresh()
            assertEquals(normal, fixture.rows.getValue(3).durationMs)
            assertEquals(listOf("item-3"), fixture.probes)
            fixture.durations.refresh()
            assertEquals(1, fixture.probes.size, "An unchanged file must not be probed again")
        }
    }

    @Test
    fun `series and seasons keep separate duration cohorts`() {
        val rows = (1L..4).map { row(it) } + (5L..8).map { row(it, 60 * minute, season = 2) } +
            (9L..12).map { row(it, 3 * minute, server = 2) }
        assertTrue(DurationOutliers.ids(rows).isEmpty())
        assertTrue(DurationOutliers.ids(listOf(row(1), row(2, normal * 10))).isEmpty(), "Insufficient evidence must not trigger a probe")
        assertFalse(1L in DurationOutliers.ids(listOf(row(1, normal * 10, season = 0), row(2), row(3), row(4))))
    }

    @Test
    fun `legitimately long episodes keep their measured length`() = runTest {
        val fixture = Fixture(normal * 10, measured = normal * 10)
        fixture.durations.refresh()
        fixture.durations.refresh()
        assertEquals(normal * 10, fixture.rows.getValue(3).durationMs)
        assertEquals(1, fixture.probes.size)
    }

    @Test
    fun `failed probes preserve metadata and may retry`() = runTest {
        val fixture = Fixture(normal * 10, measured = null)
        fixture.durations.refresh()
        assertEquals(normal * 10, fixture.rows.getValue(3).durationMs)
        fixture.measured = normal
        fixture.durations.refresh()
        assertEquals(normal, fixture.rows.getValue(3).durationMs)
        assertEquals(2, fixture.probes.size)
    }

    @Test
    fun `sync cannot overwrite a measurement with the same broken runtime`() = runTest {
        val fixture = Fixture(normal * 10)
        val incoming = entity(fixture.rows.getValue(3))
        fixture.durations.refresh()
        assertEquals(normal, fixture.durations.preserve(listOf(incoming)).single().durationMs)
        fixture.durations.store(listOf(incoming))
        assertEquals(normal, fixture.rows.getValue(3).durationMs)
        fixture.rows[3] = fixture.rows.getValue(3).copy(durationMs = normal * 10)
        fixture.durations.refresh()
        assertEquals(normal, fixture.rows.getValue(3).durationMs)
        assertEquals(1, fixture.probes.size)
    }

    @Test
    fun `a replaced source or changed reported runtime invalidates the correction`() = runTest {
        val fixture = Fixture(normal * 10)
        fixture.durations.refresh()
        val replaced = fixture.rows.getValue(3).copy(mediaSourceId = "new-source", durationMs = normal * 10)
        assertEquals(normal * 10, fixture.durations.preserve(listOf(entity(replaced))).single().durationMs)
        val changed = fixture.rows.getValue(3).copy(durationMs = normal * 20)
        assertEquals(normal * 20, fixture.durations.preserve(listOf(entity(changed))).single().durationMs)
        fixture.rows[3] = replaced
        fixture.durations.refresh()
        assertEquals(2, fixture.probes.size)
    }

    @Test
    fun `natural playback measurements survive subsequent syncs`() = runTest {
        val fixture = Fixture(normal * 10)
        val incoming = entity(fixture.rows.getValue(3))
        fixture.durations.remember(incoming, normal)
        assertEquals(normal, fixture.durations.preserve(listOf(incoming)).single().durationMs)
        fixture.durations.refresh()
        assertTrue(fixture.probes.isEmpty())
    }
}
