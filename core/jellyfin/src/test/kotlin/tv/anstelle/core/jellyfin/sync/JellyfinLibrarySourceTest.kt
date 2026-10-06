package tv.anstelle.core.jellyfin.sync

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import tv.anstelle.core.jellyfin.FixtureApiClient
import tv.anstelle.core.jellyfin.Fixtures
import tv.anstelle.core.jellyfin.toEpochMs
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
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
                RemoteLibrary("fa110000000000000000000000000006", "Collections", "boxsets"),
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

        assertEquals(5, page.items.size)
        val query = api.requests.single().query
        // The SDK sends this with the device's UTC offset; it must be the same instant.
        assertEquals(since, (query["minDateLastSaved"] as LocalDateTime).toEpochMs())
        assertEquals(150, query["startIndex"])
        assertEquals(true, query["recursive"])

        source.items("fa11000000000000000000000000000d", setOf(BaseItemKind.EPISODE), null, startIndex = 0, limit = 150)
        assertNull(api.requests.last().query["minDateLastSaved"])
    }

    @Test
    fun `box sets and their items`() = runBlocking {
        val api = FixtureApiClient(mapOf("/Items" to "box_sets_derived.json"))
        val source = JellyfinLibrarySource(api, userId)
        assertEquals(
            listOf(
                RemoteBoxSet("fa110000000000000000000000000040", "Example Saga Collection"),
                RemoteBoxSet("fa110000000000000000000000000041", "Example Cartoon Shorts"),
            ),
            source.boxSets(),
        )
        val listing = api.requests.single().query
        assertEquals(true, listing["recursive"])
        assertEquals(BaseItemKind.BOX_SET, (listing["includeItemTypes"] as Collection<*>).single())

        val members = JellyfinLibrarySource(FixtureApiClient(mapOf("/Items" to "box_set_items_derived.json")), userId)
            .boxSetItemIds("fa110000000000000000000000000040")
        assertEquals(listOf("fa110000000000000000000000000042", "fa11000000000000000000000000001a"), members)
    }

    @Test
    fun `segments decode`() = runBlocking {
        val api = FixtureApiClient(mapOf("/MediaSegments/{itemId}" to "media_segments.json"))
        val segments = JellyfinLibrarySource(api, userId).segments("fa110000000000000000000000000026")
        assertEquals(2, segments.size)
    }
}
