package tv.anstelle.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.LogLevel
import javax.inject.Inject

@HiltAndroidApp
class AnstelleApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var okHttpClient: Lazy<OkHttpClient>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Keep this method tiny: it runs before the first frame on every cold start.
        AppLog.minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO
        AppLog.addSink { entry ->
            val priority = when (entry.level) {
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }
            val message = entry.throwable?.let { "${entry.message}\n$it" } ?: entry.message
            Log.println(priority, "Anstelle/${entry.tag}", message)
        }
    }

    /** Posters and logos load through the app's one OkHttp client, built on first use. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get() })) }
            .crossfade(true)
            .build()
}
