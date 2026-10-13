package tv.anstelle.webserver.api

import kotlinx.coroutines.flow.first
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.ThemeRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.backup.BackupRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.database.dao.PairedClientDao
import javax.inject.Inject

/** The TV itself for the web UI: the home's overview and the browsers paired with it. */
interface DeviceApi {
    suspend fun overview(): OverviewDto

    /** Web control's addresses and paired clients; [you] is the client asking. */
    suspend fun web(you: Long): WebDto

    suspend fun hasClient(clientId: Long): Boolean
}

internal class RoomDeviceApi @Inject constructor(
    private val servers: ServerRepository,
    private val channels: ChannelRepository,
    private val themes: ThemeRepository,
    private val backups: BackupRepository,
    private val profiles: ProfileRepository,
    private val webServer: WebServerController,
    private val pairedClients: PairedClientDao,
) : DeviceApi {
    override suspend fun overview(): OverviewDto {
        val serverList = servers.observeServers().first()
        themes.seedBuiltIns()
        val themeList = themes.observeThemes().first()
        return OverviewDto(
            servers = serverList.size,
            serverName = serverList.singleOrNull()?.name,
            serversNeedAttention = serverList.any { !it.isSignedIn || it.lastSyncError != null },
            channels = channels.observeChannels().first().size,
            customThemes = themeList.count { !it.isBuiltIn },
            builtInThemes = themeList.count { it.isBuiltIn },
            pairedClients = pairedClients.observeAll().first().size,
            backupSavedUtcMs = backups.observeSummary().first().savedUtcMs,
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
