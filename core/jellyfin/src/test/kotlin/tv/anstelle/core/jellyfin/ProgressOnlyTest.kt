package tv.anstelle.core.jellyfin

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.serializer.toUUID
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressOnlyTest {
    private class RecordingApi : ApiClient() {
        override val baseUrl = "http://jellyfin.test"
        override val accessToken = "test-token"
        override val clientInfo = ClientInfo("Tests", "1")
        override val deviceInfo = DeviceInfo("test-device", "Tests")
        override val httpClientOptions = HttpClientOptions()
        override val webSocket: SocketApi get() = error("Unused")
        val bodies = ArrayList<UpdateUserItemDataDto>()
        val sessions = ArrayList<Any>()
        val paths = ArrayList<String>()
        override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) = Unit
        override suspend fun request(method: HttpMethod, pathTemplate: String, pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>, requestBody: Any?): RawResponse {
            paths += pathTemplate
            if (requestBody is UpdateUserItemDataDto) bodies += requestBody else sessions += requireNotNull(requestBody)
            val response = """{"PlaybackPositionTicks":9900000000,"PlayCount":0,"IsFavorite":false,"Played":false,"Key":"test","ItemId":"00000000000000000000000000000002"}"""
            return RawResponse(response.toByteArray(), 200, emptyMap())
        }
    }

    @Test
    fun `resume updates never send played flags play counts or session reports`() = runBlocking {
        val api = RecordingApi()
        val playstate = JellyfinPlaystate(api, "00000000000000000000000000000001")
        playstate.saveProgress("00000000000000000000000000000002", 990_000, 0)
        val body = api.bodies.single()
        assertEquals(9_900_000_000, body.playbackPositionTicks)
        assertNull(body.played)
        assertNull(body.playCount)
        assertTrue(api.paths.none { it.startsWith("/Sessions") })
    }

    @Test
    fun `progress presence never identifies a library item even for timeout cleanup`() = runBlocking {
        val api = RecordingApi()
        val playstate = JellyfinPlaystate(api, "00000000000000000000000000000001")
        val presence = PlaybackPresence(UUID.randomUUID().toString(), "Episode", "Series", 1_000_000)
        val item = PlaystateItem("00000000000000000000000000000002", "source", "session", false, presence)
        playstate.started(item, 20_000)
        playstate.progress(item, 30_000, true)
        playstate.stopped(item, 40_000)
        val start = api.sessions[0] as PlaybackStartInfo
        val progress = api.sessions[1] as PlaybackProgressInfo
        val stop = api.sessions[2] as PlaybackStopInfo
        assertEquals(UUID(0, 0), start.itemId)
        assertEquals(UUID(0, 0), progress.itemId)
        assertEquals(UUID(0, 0), stop.itemId)
        assertEquals(presence.id.toUUID(), start.item?.id)
        assertEquals(start.item?.id, progress.item?.id)
        assertEquals(start.item?.id, stop.item?.id)
        assertTrue(start.item?.id != item.itemId.toUUID())
        assertEquals(300_000_000L, progress.positionTicks)
        assertTrue(progress.isPaused)
        assertTrue(api.bodies.isEmpty())
    }

    @Test
    fun `status updates only the played flag`() = runBlocking {
        val api = RecordingApi()
        JellyfinPlaystate(api, "00000000000000000000000000000001")
            .setPlayed("00000000000000000000000000000002", true)
        val body = api.bodies.single()
        assertEquals(true, body.played)
        assertNull(body.playbackPositionTicks)
        assertNull(body.playCount)
        assertNull(body.lastPlayedDate)
    }
}
