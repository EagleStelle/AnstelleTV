package tv.anstelle.core.jellyfin.sync

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import tv.anstelle.core.jellyfin.FixtureApiClient
import tv.anstelle.core.jellyfin.Fixtures
import tv.anstelle.core.jellyfin.toEpochMs
import tv.anstelle.core.jellyfin.toJellyfinId
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Runs the real SDK request and decoding path against recorded server responses. */
class JellyfinLibrarySourceTest {
    private val originalZone = Fixtures.useTestZone()
    private val userId = "fa1100000000000000000000000000ff"

    @AfterTest
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun `grouped views resolve to their real libraries`() = runBlocking {
        val api = FixtureApiClient(
            mapOf(
                "/UserViews" to "user_views.json",
                "/UserViews/GroupingOptions" to "grouping_options.json",
                "/Items/fa11000000000000000000000000000d" to "library_folder_cartoons.json",
                "/Items/fa11000000000000000000000000000e" to "library_folder_dramas.json",
            ),
        )
        val libraries = JellyfinLibrarySource(api, userId).libraries()
        assertEquals(
            listOf(
                RemoteLibrary("fa110000000000000000000000000002", "Music", "music"),
                RemoteLibrary("fa11000000000000000000000000000d", "Cartoons", "tvshows"),
                RemoteLibrary("fa11000000000000000000000000000e", "Dramas", "tvshows"),
            ),
            libraries,
        )
    }

    @Test
    fun `incremental item pages ask only for changes`() = runBlocking {
        val api = FixtureApiClient(mapOf("/Items" to "items_tv_page.json"))
        val source = JellyfinLibrarySource(api, userId)
        val since = Instant.parse("2026-10-05T13:56:22Z").toEpochMilli()

        val page = source.items("fa11000000000000000000000000000d", setOf(BaseItemKind.EPISODE), since, startIndex = 150, limit = 150)

        assertEquals(5, page.size)
        val query = api.requests.single().query
        // The SDK sends this with the device's UTC offset; it must be the same instant.
        assertEquals(since, (query["minDateLastSaved"] as LocalDateTime).toEpochMs())
        assertEquals(150, query["startIndex"])
        assertEquals(true, query["recursive"])
        // Nothing reads a total, so the server isn't asked to count the library for every page.
        assertEquals(false, query["enableTotalRecordCount"])

        source.items("fa11000000000000000000000000000d", setOf(BaseItemKind.EPISODE), null, startIndex = 0, limit = 150)
        assertNull(api.requests.last().query["minDateLastSaved"])
    }

    @Test
    fun `items by id are asked for a hundred at a time below their library`() = runBlocking {
        val api = FixtureApiClient(mapOf("/Items" to "items_tv_page.json"))
        val ids = List(150) { "fa11%028x".format(it) }

        JellyfinLibrarySource(api, userId).itemsByIds("fa11000000000000000000000000000d", setOf(BaseItemKind.EPISODE), ids)

        assertEquals(listOf(100, 50), api.requests.map { (it.query["ids"] as Collection<*>).size })
        assertEquals("fa11000000000000000000000000000d", (api.requests.first().query["parentId"] as UUID).toJellyfinId())
        assertEquals(true, api.requests.first().query["recursive"])
        assertNull(api.requests.first().query["startIndex"])
    }
}
