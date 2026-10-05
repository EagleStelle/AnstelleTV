package tv.anstelle.core.jellyfin

import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.PublicSystemInfo
import tv.anstelle.core.model.PublicServerInfo
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import javax.inject.Inject
import javax.inject.Singleton

/** Finds and checks Jellyfin servers without signing in. */
@Singleton
class ServerProbe @Inject constructor(
    private val jellyfin: Jellyfin,
    private val clients: JellyfinClients,
) {
    /**
     * Finds the server behind what the user typed: an IP address, a host name or a full
     * URL. Like the official Jellyfin apps, it lets the SDK expand the input into
     * candidate URLs (with and without HTTPS, default ports), checks them all and keeps
     * the best-scoring one.
     */
    suspend fun find(address: String): PublicServerInfo {
        val candidates = try {
            jellyfin.discovery.getAddressCandidates(address.trim()).toList()
        } catch (e: IllegalArgumentException) {
            throw ServerConnectException(ServerProblem.INVALID_URL, "Invalid address: $address", e)
        }
        if (candidates.isEmpty()) throw ServerConnectException(ServerProblem.INVALID_URL, "Invalid address: $address")

        val results = try {
            jellyfin.discovery.getRecommendedServers(candidates, RecommendedServerInfoScore.BAD)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ServerConnectException(ServerProblem.UNREACHABLE, "Could not check $address: ${e.message}", e)
        }
        val best = results
            .filter { it.score != RecommendedServerInfoScore.BAD }
            .sortedWith(compareBy({ it.score.ordinal }, { candidates.indexOf(it.address) }))
            .firstOrNull()
            ?: throw results.toFailure(address)
        val info = best.systemInfo.getOrNull()
            ?: throw ServerConnectException(ServerProblem.NOT_JELLYFIN, "${best.address} is not a Jellyfin server")
        return info.toPublicInfo(best.address)
    }

    /** Checks a known address and reports which server answers there now. */
    suspend fun probe(url: String): PublicServerInfo {
        val info = jellyfinCall("Contacting $url") {
            clients.probeApi(url).systemApi.getPublicSystemInfo().content
        }
        return info.toPublicInfo(url)
    }

    private fun PublicSystemInfo.toPublicInfo(url: String): PublicServerInfo {
        val serverId = id?.takeIf { it.isNotBlank() }
            ?: throw ServerConnectException(ServerProblem.NOT_JELLYFIN, "$url is not a Jellyfin server")
        return PublicServerInfo(
            id = normalizeJellyfinId(serverId),
            name = serverName?.takeIf { it.isNotBlank() } ?: "Jellyfin",
            version = version.orEmpty(),
            url = url,
        )
    }

    /** Explains why no candidate worked, using the most telling issue any of them had. */
    private fun Collection<RecommendedServerInfo>.toFailure(address: String): ServerConnectException {
        val issues = flatMap { it.issues }
        return when {
            issues.any { it is RecommendedServerIssue.UnsupportedServerVersion || it is RecommendedServerIssue.OutdatedServerVersion } ->
                ServerConnectException(ServerProblem.UNSUPPORTED_VERSION, "$address runs a Jellyfin version that is too old")
            issues.any { it is RecommendedServerIssue.InvalidProductName } ->
                ServerConnectException(ServerProblem.NOT_JELLYFIN, "$address is not a Jellyfin server")
            else -> ServerConnectException(ServerProblem.UNREACHABLE, "No Jellyfin server answered at $address")
        }
    }
}
