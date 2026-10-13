package tv.anstelle.webserver.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.ServerRepository
import tv.anstelle.core.data.WebServerController
import tv.anstelle.webserver.ServerDependencies
import tv.anstelle.webserver.WebServerControllerImpl
import tv.anstelle.webserver.api.BackupApi
import tv.anstelle.webserver.api.ChannelsApi
import tv.anstelle.webserver.api.DeviceApi
import tv.anstelle.webserver.api.RoomBackupApi
import tv.anstelle.webserver.api.RoomChannelsApi
import tv.anstelle.webserver.api.RoomDeviceApi
import tv.anstelle.webserver.api.RoomServersApi
import tv.anstelle.webserver.api.RoomThemesApi
import tv.anstelle.webserver.api.ServersApi
import tv.anstelle.webserver.api.ThemesApi
import tv.anstelle.webserver.http.AndroidAssetSource
import tv.anstelle.webserver.http.StaticFiles
import tv.anstelle.webserver.pairing.PairedClientStore
import tv.anstelle.webserver.pairing.PairingGate
import tv.anstelle.webserver.pairing.PairingManager
import tv.anstelle.webserver.pairing.ProfilePairingGate
import tv.anstelle.webserver.pairing.RoomPairedClientStore
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WebServerBindings {
    @Binds
    abstract fun controller(impl: WebServerControllerImpl): WebServerController

    @Binds
    abstract fun pairedClientStore(impl: RoomPairedClientStore): PairedClientStore

    @Binds
    abstract fun pairingGate(impl: ProfilePairingGate): PairingGate

    @Binds
    abstract fun channels(impl: RoomChannelsApi): ChannelsApi

    @Binds
    abstract fun servers(impl: RoomServersApi): ServersApi

    @Binds
    abstract fun themes(impl: RoomThemesApi): ThemesApi

    @Binds
    abstract fun backup(impl: RoomBackupApi): BackupApi

    @Binds
    abstract fun device(impl: RoomDeviceApi): DeviceApi
}

@Module
@InstallIn(SingletonComponent::class)
internal object WebServerModule {
    @Provides
    @Singleton
    fun staticFiles(@ApplicationContext context: Context): StaticFiles =
        StaticFiles(AndroidAssetSource(context.assets))

    @OptIn(FlowPreview::class)
    @Provides
    fun serverDependencies(
        appInfo: AppInfo,
        pairing: PairingManager,
        staticFiles: StaticFiles,
        channels: ChannelsApi,
        servers: ServersApi,
        themes: ThemesApi,
        backup: BackupApi,
        device: DeviceApi,
        changes: DatabaseChanges,
        serverRepository: ServerRepository,
    ): ServerDependencies {
        // A running sync isn't a table, so its progress reaches browsers as one, at most once a second.
        val syncing = serverRepository.syncProgress.distinctUntilChanged().sample(1.seconds).map { setOf(SYNC) }
        return ServerDependencies(appInfo, pairing, staticFiles, channels, servers, themes, backup, device, merge(changes.tables, syncing))
    }

    private const val SYNC = "sync"
}
