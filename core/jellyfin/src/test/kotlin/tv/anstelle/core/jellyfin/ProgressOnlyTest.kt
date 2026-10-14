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
        val paths = ArrayList<String>()
        override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) = Unit
        override suspend fun request(method: HttpMethod, pathTemplate: String, pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>, requestBody: Any?): RawResponse {
            paths += pathTemplate
            bodies += requestBody as UpdateUserItemDataDto
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
}
