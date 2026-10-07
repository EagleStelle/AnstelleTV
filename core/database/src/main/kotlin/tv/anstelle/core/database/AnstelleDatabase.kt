package tv.anstelle.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
import tv.anstelle.core.database.entity.ChannelDaypartEntity
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelGroupEntity
import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity
import tv.anstelle.core.database.entity.DismissedChannelEntity
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.LiveChannelEntity
import tv.anstelle.core.database.entity.LiveProgramEntity
import tv.anstelle.core.database.entity.LiveSourceEntity
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.database.entity.ScheduleStateEntity
import tv.anstelle.core.database.entity.SettingEntity
import tv.anstelle.core.database.entity.ThemeEntity
import tv.anstelle.core.database.entity.WatchEventEntity

/**
 * The single source of truth. The TV UI observes it through Room Flows and the web UI
 * gets invalidation events for the same tables over the WebSocket.
 */
@Database(
    entities = [
        MediaServerEntity::class,
        ProfileEntity::class,
        LibraryEntity::class,
        MediaItemEntity::class,
        ChannelGroupEntity::class,
        ChannelEntity::class,
        ChannelRuleEntity::class,
        ChannelSeasonEntity::class,
        ChannelDaypartEntity::class,
        DismissedChannelEntity::class,
        LiveSourceEntity::class,
        LiveChannelEntity::class,
        LiveProgramEntity::class,
        ScheduleSlotEntity::class,
        ScheduleStateEntity::class,
        ThemeEntity::class,
        WatchEventEntity::class,
        PairedClientEntity::class,
        SettingEntity::class,
    ],
    version = AnstelleDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AnstelleDatabase : RoomDatabase() {
    abstract fun mediaServerDao(): MediaServerDao
    abstract fun libraryDao(): LibraryDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun profileDao(): ProfileDao
    abstract fun channelDao(): ChannelDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun liveTvDao(): LiveTvDao
    abstract fun themeDao(): ThemeDao
    abstract fun watchEventDao(): WatchEventDao
    abstract fun pairedClientDao(): PairedClientDao
    abstract fun settingDao(): SettingDao

    companion object {
        const val VERSION = 4
        const val NAME = "anstelle.db"

        /** Tables the web UI can subscribe to for live updates. */
        val OBSERVED_TABLES = arrayOf(
            "media_server", "profile", "library", "media_item", "channel_group", "channel",
            "channel_rule", "channel_season", "channel_daypart", "dismissed_channel", "live_source", "live_channel",
            "live_program", "schedule_slot", "theme", "watch_event", "paired_client", "setting",
        )

        /**
         * Builds the database. [allowDestructiveMigration] is only true in debug builds
         * while the schema is still moving between development phases; release builds
         * must ship real migrations.
         */
        fun build(context: Context, allowDestructiveMigration: Boolean): AnstelleDatabase =
            Room.databaseBuilder(context, AnstelleDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Schemas from before the first public release hold nothing worth migrating.
                .fallbackToDestructiveMigrationFrom(true, *PRE_RELEASE_VERSIONS)
                .apply { if (allowDestructiveMigration) fallbackToDestructiveMigration(dropAllTables = true) }
                .build()

        private val PRE_RELEASE_VERSIONS = intArrayOf(1, 2, 3)
    }
}
