package tv.anstelle.app

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.anstelle.core.common.AppInfo
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun appInfo(@ApplicationContext context: Context): AppInfo = AppInfo(
        appName = context.getString(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        deviceName = context.deviceDisplayName(),
        isTv = context.isTvDevice(),
        isDebug = BuildConfig.DEBUG,
    )
}
