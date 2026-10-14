package tv.anstelle.core.jellyfin.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The app's single HTTP stack. Jellyfin API calls, image loading and media streaming all
 * share this [OkHttpClient], so they share one connection pool and keep-alive connections
 * to the server.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        // LAN servers answer fast; a short connect timeout makes the local/remote probe snappy.
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun jellyfin(@ApplicationContext context: Context, okHttpClient: OkHttpClient): Jellyfin {
        // OkHttpFactory derives its clients from this base, sharing its pool and dispatcher.
        val factory = OkHttpFactory(okHttpClient)
        return createJellyfin {
            this.context = context
            clientInfo = ClientInfo(name = CLIENT_NAME, version = appVersion(context))
            deviceInfo = androidDevice(context)
            apiClientFactory = factory
            socketConnectionFactory = factory
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    }.getOrNull() ?: "0"

    const val CLIENT_NAME = "AnstelleTV"
    private const val MAX_IDLE_CONNECTIONS = 8
    private const val KEEP_ALIVE_MINUTES = 5L
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 30L
}
