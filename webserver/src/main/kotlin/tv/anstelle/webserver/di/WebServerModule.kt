package tv.anstelle.webserver.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.WebServerController
import tv.anstelle.webserver.ServerDependencies
import tv.anstelle.webserver.WebServerControllerImpl
import tv.anstelle.webserver.api.ApiDataSource
import tv.anstelle.webserver.api.RoomApiDataSource
import tv.anstelle.webserver.http.AndroidAssetSource
import tv.anstelle.webserver.http.StaticFiles
import tv.anstelle.webserver.pairing.PairedClientStore
import tv.anstelle.webserver.pairing.PairingManager
import tv.anstelle.webserver.pairing.RoomPairedClientStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WebServerBindings {
    @Binds
    abstract fun controller(impl: WebServerControllerImpl): WebServerController

    @Binds
    abstract fun pairedClientStore(impl: RoomPairedClientStore): PairedClientStore

    @Binds
    abstract fun apiDataSource(impl: RoomApiDataSource): ApiDataSource
}

@Module
@InstallIn(SingletonComponent::class)
internal object WebServerModule {
    @Provides
    @Singleton
    fun staticFiles(@ApplicationContext context: Context): StaticFiles =
        StaticFiles(AndroidAssetSource(context.assets))

    @Provides
    fun serverDependencies(
        appInfo: AppInfo,
        pairing: PairingManager,
        staticFiles: StaticFiles,
        api: ApiDataSource,
        changes: DatabaseChanges,
    ): ServerDependencies = ServerDependencies(appInfo, pairing, staticFiles, api, changes.tables)
}
