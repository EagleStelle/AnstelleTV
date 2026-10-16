package tv.anstelle.core.jellyfin

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.serializer.toUUID
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogPlaystateTest {
    private val userId = "00000000000000000000000000000001"

    @Test
    fun `next up drops watched specials and regular episodes but keeps unwatched specials in server order`() = runBlocking {
        val api = FixtureApiClient(mapOf("/Shows/NextUp" to "catalog_next_up.json"))
        val next = JellyfinPlaystate(api, userId).nextUp(24)

        assertEquals(listOf("00000000000000000000000000000012", "00000000000000000000000000000014"), next)
        val query = api.requests.single().query
        assertEquals(userId.toUUID(), query["userId"])
        assertEquals(24, query["limit"])
        assertEquals(true, query["enableUserData"])
        assertEquals(false, query["enableResumable"])
        assertEquals(false, query["enableRewatching"])
        assertEquals(false, query["enableImages"])
        assertEquals(false, query["enableTotalRecordCount"])
    }

    @Test
    fun `continue watching uses Jellyfin resume points including specials and rewatches without local watched filtering`() = runBlocking {
        for (episodes in listOf(true, false)) {
            val api = FixtureApiClient(mapOf("/UserItems/Resume" to "catalog_resume.json"))
            val points = JellyfinPlaystate(api, userId).resumeItems(episodes, 24)

            assertEquals(listOf(
                ResumePoint("00000000000000000000000000000011", 300_000, Instant.parse("2026-10-16T02:00:00Z").toEpochMilli()),
                ResumePoint("00000000000000000000000000000012", 120_000, Instant.parse("2026-10-15T02:00:00Z").toEpochMilli()),
            ), points)
            val query = api.requests.single().query
            assertEquals(userId.toUUID(), query["userId"])
            assertEquals(listOf(if (episodes) BaseItemKind.EPISODE else BaseItemKind.MOVIE), query["includeItemTypes"])
            assertEquals(listOf(MediaType.VIDEO), query["mediaTypes"])
            assertEquals(24, query["limit"])
            assertEquals(true, query["enableUserData"])
        }
    }
}
