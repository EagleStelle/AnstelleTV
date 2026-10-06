package tv.anstelle.core.data.di

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.ApplicationScope
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.channels.AssetTemplateSource
import tv.anstelle.core.data.channels.TemplateSource
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.dao.ChannelDao
import tv.anstelle.core.database.dao.LibraryDao
import tv.anstelle.core.database.dao.LiveTvDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.dao.PairedClientDao
import tv.anstelle.core.database.dao.ProfileDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.dao.SettingDao
import tv.anstelle.core.database.dao.ThemeDao
import tv.anstelle.core.database.dao.WatchEventDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
    @Provides
    @Dispatcher(AnstelleDispatchers.IO)
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(AnstelleDispatchers.Default)
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(
        @Dispatcher(AnstelleDispatchers.Default) dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    fun timeSource(): TimeSource = TimeSource.System
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChannelsModule {
    @Binds
    abstract fun templateSource(source: AssetTemplateSource): TemplateSource
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AnstelleDatabase {
        // Debug builds may drop the database when the schema changes between development
        // phases. Release builds must ship migrations.
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return AnstelleDatabase.build(context, allowDestructiveMigration = debuggable)
    }

    @Provides fun mediaServerDao(db: AnstelleDatabase): MediaServerDao = db.mediaServerDao()
    @Provides fun libraryDao(db: AnstelleDatabase): LibraryDao = db.libraryDao()
    @Provides fun mediaItemDao(db: AnstelleDatabase): MediaItemDao = db.mediaItemDao()
    @Provides fun profileDao(db: AnstelleDatabase): ProfileDao = db.profileDao()
    @Provides fun channelDao(db: AnstelleDatabase): ChannelDao = db.channelDao()
    @Provides fun scheduleDao(db: AnstelleDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun liveTvDao(db: AnstelleDatabase): LiveTvDao = db.liveTvDao()
    @Provides fun themeDao(db: AnstelleDatabase): ThemeDao = db.themeDao()
    @Provides fun watchEventDao(db: AnstelleDatabase): WatchEventDao = db.watchEventDao()
    @Provides fun pairedClientDao(db: AnstelleDatabase): PairedClientDao = db.pairedClientDao()
    @Provides fun settingDao(db: AnstelleDatabase): SettingDao = db.settingDao()
}
