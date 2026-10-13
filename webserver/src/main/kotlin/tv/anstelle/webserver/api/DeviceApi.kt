package tv.anstelle.webserver.api

import kotlinx.coroutines.flow.first
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelStats
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.CatalogRepository
import tv.anstelle.core.data.ondemand.OnDemandSummary
import tv.anstelle.core.database.dao.PairedClientDao
import tv.anstelle.core.model.MediaType
import javax.inject.Inject

/** The TV itself for the web UI: the home's rows and readouts, and the browsers paired with it. */
interface DeviceApi {
    suspend fun overview(): OverviewDto

    /** Web control's addresses and paired clients; [you] is the client asking. */
    suspend fun web(you: Long): WebDto

    suspend fun hasClient(clientId: Long): Boolean
}

internal class RoomDeviceApi @Inject constructor(
    private val servers: ServerRepository,
    private val channels: ChannelRepository,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val webServer: WebServerController,
    private val pairedClients: PairedClientDao,
) : DeviceApi {
    override suspend fun overview(): OverviewDto {
        val serverList = servers.observeServers().first()
        val syncing = servers.syncProgress.first()
        val held = serverList.flatMap { servers.observeItemCounts(it.id).first() }.groupBy({ it.type }, { it.count }).mapValues { it.value.sum() }
        val channelList = channels.observeChannels().first()
        val stats = ChannelStats.of(channelList)
        val onDemand = OnDemandSummary.of(
            homeBanner = settings.get(Settings.OnDemandBanner),
            banners = CatalogKind.entries.associateWith { settings.get(Settings.catalogBanner(it)) },
            shelves = CatalogKind.entries.associateWith { catalog.shelves(it) },
        )
        return OverviewDto(
            servers = ServersOverviewDto(
                count = serverList.size,
                name = serverList.singleOrNull()?.name,
                needAttention = serverList.count { !it.isSignedIn || it.lastSyncError != null },
                syncing = serverList.count { it.id in syncing },
                lastSyncUtcMs = serverList.mapNotNull { it.lastSyncUtcMs }.maxOrNull(),
                movies = held[MediaType.MOVIE] ?: 0,
                series = held[MediaType.SERIES] ?: 0,
                episodes = held[MediaType.EPISODE] ?: 0,
            ),
            channels = ChannelStatsDto(stats.total, stats.custom, stats.library, stats.visible, stats.favorites, stats.hidden, stats.groups),
            onDemand = OnDemandOverviewDto(
                summary = onDemand.summary,
                homeBanner = onDemand.homeBanner,
                kinds = CatalogKind.entries.map { KindOverviewDto(it.key, it.label, onDemand.banner(it), onDemand.firstRows[it].orEmpty()) },
            ),
        )
    }

    override suspend fun web(you: Long): WebDto {
        val status = webServer.status.value
        val names = profiles.observeProfiles().first().associate { it.id to it.name }
        return WebDto(
            running = status.running,
            urls = status.urls,
            clients = pairedClients.observeAll().first().map {
                ClientDto(it.id, it.name, it.lastAddress, it.userAgent, it.lastSeenUtcMs, names[it.profileId], you = it.id == you)
            },
        )
    }

    override suspend fun hasClient(clientId: Long): Boolean = pairedClients.observeAll().first().any { it.id == clientId }
}
