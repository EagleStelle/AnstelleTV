package tv.anstelle.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import tv.anstelle.core.database.dao.BackupDao
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
import tv.anstelle.core.database.entity.HiddenItemEntity
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.LiveChannelEntity
import tv.anstelle.core.database.entity.LiveProgramEntity
import tv.anstelle.core.database.entity.LiveSourceEntity
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.database.entity.ProfileAccountEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.database.entity.RestoredItemEntity
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
        ProfileAccountEntity::class,
        HiddenItemEntity::class,
        LibraryEntity::class,
        MediaItemEntity::class,
        ChannelGroupEntity::class,
        ChannelEntity::class,
        ChannelRuleEntity::class,
        ChannelSeasonEntity::class,
        ChannelDaypartEntity::class,
        LiveSourceEntity::class,
        LiveChannelEntity::class,
        LiveProgramEntity::class,
        ScheduleSlotEntity::class,
        ScheduleStateEntity::class,
        ThemeEntity::class,
        WatchEventEntity::class,
        RestoredItemEntity::class,
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
    abstract fun backupDao(): BackupDao

    companion object {
        const val VERSION = 14
        const val NAME = "anstelle.db"

        /** Tables the web UI can subscribe to for live updates. */
        val OBSERVED_TABLES = arrayOf(
            "media_server", "profile", "profile_account", "library", "media_item", "channel_group", "channel",
            "channel_rule", "channel_season", "channel_daypart", "live_source", "live_channel",
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

        private val PRE_RELEASE_VERSIONS = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)

        /** Also used when restoring backups made before server ownership was recorded. */
        fun assignServerOwners(db: SupportSQLiteDatabase) {
            db.execSQL("""
                UPDATE media_server SET ownerProfileId = NULL
                WHERE ownerProfileId IS NOT NULL AND userId IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM profile_account a WHERE a.profileId = ownerProfileId
                    AND a.serverId = media_server.id AND a.userId = media_server.userId
                )
            """.trimIndent())
            db.execSQL("""
                UPDATE media_server SET ownerProfileId = (
                    SELECT p.id FROM profile p JOIN profile_account a ON a.profileId = p.id
                    WHERE a.serverId = media_server.id AND a.userId = media_server.userId
                    ORDER BY p.role DESC, p.id LIMIT 1
                ) WHERE ownerProfileId IS NULL
            """.trimIndent())
            db.execSQL("""
                CREATE TEMP TABLE missing_connection_profile AS
                SELECT id AS serverId, (SELECT COALESCE(MAX(id), 0) FROM profile) + id AS profileId
                FROM media_server WHERE ownerProfileId IS NULL AND userId IS NOT NULL
            """.trimIndent())
            db.execSQL("""
                INSERT INTO profile (id, name, role, maxRating, pinHash, pictureUrl, themeId, sortOrder, createdAtUtcMs)
                SELECT m.profileId, COALESCE(NULLIF(s.userName, ''), s.name), 1, NULL, NULL, NULL, NULL,
                    (SELECT COALESCE(MAX(sortOrder), -1) FROM profile) + s.id, s.createdAtUtcMs
                FROM missing_connection_profile m JOIN media_server s ON s.id = m.serverId
            """.trimIndent())
            // These accounts have no token until the connection user signs in again; server sessions are kept.
            db.execSQL("""
                INSERT INTO profile_account (profileId, serverId, userId, userName, device)
                SELECT m.profileId, s.id, s.userId, COALESCE(NULLIF(s.userName, ''), s.name), 'connection-' || s.id
                FROM missing_connection_profile m JOIN media_server s ON s.id = m.serverId
            """.trimIndent())
            db.execSQL("""
                UPDATE media_server SET ownerProfileId = (SELECT m.profileId FROM missing_connection_profile m WHERE m.serverId = media_server.id)
                WHERE id IN (SELECT serverId FROM missing_connection_profile)
            """.trimIndent())
            db.execSQL("DROP TABLE missing_connection_profile")
            db.execSQL("UPDATE profile SET role = 1, maxRating = NULL WHERE role = 0 AND id IN (SELECT ownerProfileId FROM media_server)")
            db.execSQL("UPDATE media_server SET ownerProfileId = (SELECT id FROM profile WHERE role = 2 LIMIT 1) WHERE ownerProfileId IS NULL")
        }
    }
}
