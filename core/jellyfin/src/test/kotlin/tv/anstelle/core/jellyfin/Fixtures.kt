package tv.anstelle.core.jellyfin

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.util.TimeZone
import java.util.UUID

/**
 * JSON recorded from a Jellyfin 12.1.0 server, then anonymized: ids, titles, studios, tags,
 * overviews and dates are replaced and file paths removed, while the structure and the
 * technical fields (streams, ticks, ratings) stay as the server sent them.
 * `items_movie_page_derived.json` is built from a recorded episode, because the test
 * account has no movie library.
 */
object Fixtures {
    fun text(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "Missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    /** Decodes a fixture the same way the SDK decodes server responses. */
    inline fun <reified T> decode(name: String): T = ApiSerializer.json.decodeFromString(text(name))

    /**
     * Makes [TestZone] the default zone and returns the previous one. The SDK converts server
     * times to the device zone, so a zone far from UTC catches UTC/local mix-ups on any machine.
     */
    fun useTestZone(): TimeZone = TimeZone.getDefault().also { TimeZone.setDefault(TestZone) }

    val TestZone: TimeZone = TimeZone.getTimeZone("Asia/Manila")
}

/** An [ApiClient] that answers from fixtures by path template and records each request. */
class FixtureApiClient(private val routes: Map<String, String>) : ApiClient() {
    data class Request(val path: String, val pathParameters: Map<String, Any?>, val query: Map<String, Any?>)

    val requests = mutableListOf<Request>()

    override val baseUrl: String = "http://jellyfin.test:8096"
    override val accessToken: String = "token"
    override val clientInfo = ClientInfo("AnstelleTV tests", "1")
    override val deviceInfo = DeviceInfo("test-device", "Test device")
    override val httpClientOptions = HttpClientOptions()
    override val webSocket: SocketApi get() = throw UnsupportedOperationException()

    override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) = Unit

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse {
        requests += Request(pathTemplate, pathParameters, queryParameters)
        val key = pathParameters.entries.fold(pathTemplate) { path, (name, value) ->
            path.replace("{$name}", (value as? UUID)?.toJellyfinId() ?: value.toString())
        }
        val fixture = routes[key] ?: routes[pathTemplate] ?: error("No fixture for $method $key")
        return RawResponse(Fixtures.text(fixture).toByteArray(), 200, emptyMap())
    }
}
