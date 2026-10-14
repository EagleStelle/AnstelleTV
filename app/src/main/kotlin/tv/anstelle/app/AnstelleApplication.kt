package tv.anstelle.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
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
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, IMAGE_MEMORY_PERCENT).build() }
            .diskCache {
                DiskCache.Builder()
                    // Coil's own folder, so what an older install cached there is trimmed to the new size.
                    .directory(cacheDir.resolve("coil3_disk_cache").toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()

    private companion object {
        /** Artwork shown at once is a few screens of posters; Coil's default is a quarter of the heap. */
        const val IMAGE_MEMORY_PERCENT = 0.12

        /** About a thousand backdrops, logos and posters; Coil's default grows to 2% of the disk (250 MB). */
        const val IMAGE_DISK_BYTES = 64L * 1024 * 1024
    }
}
