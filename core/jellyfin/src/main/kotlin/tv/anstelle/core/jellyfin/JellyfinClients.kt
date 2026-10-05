package tv.anstelle.core.jellyfin

import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Creates Jellyfin API clients. They all share the app's single OkHttp connection pool. */
@Singleton
class JellyfinClients @Inject constructor(
    private val jellyfin: Jellyfin,
) {
    fun api(baseUrl: String, accessToken: String? = null): ApiClient =
        jellyfin.createApi(baseUrl = baseUrl, accessToken = accessToken)

    /** A client with short timeouts, for checking whether a URL answers at all. */
    fun probeApi(baseUrl: String): ApiClient =
        jellyfin.createApi(baseUrl = baseUrl, httpClientOptions = ProbeOptions)

    private companion object {
        val ProbeOptions = HttpClientOptions(
            requestTimeout = 4.seconds,
            connectTimeout = 1500.milliseconds,
            socketTimeout = 4.seconds,
        )
    }
}

/** Jellyfin ids are UUIDs written as 32 lowercase hex digits, which is how they are stored. */
fun UUID.toJellyfinId(): String = toString().replace("-", "")

/** Normalizes a server or item id from any source (with or without dashes, any case). */
fun normalizeJellyfinId(id: String): String = id.replace("-", "").lowercase()

/**
 * Runs a Jellyfin SDK call and turns its failures into [ServerConnectException]. A 401 or
 * 403 becomes [unauthorized]; coroutine cancellation is passed through untouched.
 */
suspend fun <T> jellyfinCall(
    what: String,
    unauthorized: ServerProblem = ServerProblem.SIGNED_OUT,
    block: suspend () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ServerConnectException) {
    throw e
} catch (e: InvalidStatusException) {
    val problem = if (e.status == 401 || e.status == 403) unauthorized else ServerProblem.SERVER_ERROR
    throw ServerConnectException(problem, "$what failed: HTTP ${e.status}", e)
} catch (e: InvalidContentException) {
    throw ServerConnectException(ServerProblem.NOT_JELLYFIN, "$what: unexpected response", e)
} catch (e: ApiClientException) {
    throw ServerConnectException(ServerProblem.UNREACHABLE, "$what: ${e.cause?.message ?: e.message}", e)
} catch (e: IOException) {
    throw ServerConnectException(ServerProblem.UNREACHABLE, "$what: ${e.message}", e)
} catch (e: IllegalArgumentException) {
    throw ServerConnectException(ServerProblem.INVALID_URL, "$what: ${e.message}", e)
}
