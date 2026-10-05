package tv.anstelle.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.LogLevel
import javax.inject.Inject

@HiltAndroidApp
class AnstelleApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

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
}
