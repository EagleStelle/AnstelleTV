package tv.anstelle.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.database.entity.HiddenItemEntity
import tv.anstelle.core.database.entity.LiveChannelEntity
import tv.anstelle.core.database.entity.LiveProgramEntity
import tv.anstelle.core.database.entity.LiveSourceEntity
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.database.entity.ProfileAccountEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.database.entity.SettingEntity
import tv.anstelle.core.database.entity.ThemeEntity
import tv.anstelle.core.database.entity.WatchEventEntity

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile ORDER BY sortOrder, id")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun byId(id: Long): ProfileEntity?

    @Query("SELECT * FROM profile WHERE id = :id")
    fun observe(id: Long): Flow<ProfileEntity?>


    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM profile")
    suspend fun maxSortOrder(): Int

    @Upsert
    suspend fun upsert(profile: ProfileEntity): Long

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun delete(id: Long)

    /** Profiles on a theme that is going away fall back to the default. */
    @Query("UPDATE profile SET themeId = NULL WHERE themeId = :themeId")
    suspend fun clearTheme(themeId: Long)

    /** The profile's own Jellyfin users, first server first. */
    @Query("SELECT * FROM profile_account WHERE profileId = :profileId ORDER BY serverId")
    suspend fun accounts(profileId: Long): List<ProfileAccountEntity>

    @Query("SELECT * FROM profile_account WHERE profileId = :profileId ORDER BY serverId")
    fun observeAccounts(profileId: Long): Flow<List<ProfileAccountEntity>>

    /** Every profile's own user on [serverId]. */
    @Query("SELECT * FROM profile_account WHERE serverId = :serverId")
    suspend fun accountsOn(serverId: Long): List<ProfileAccountEntity>

    @Query("SELECT * FROM profile_account WHERE profileId = :profileId AND serverId = :serverId")
    suspend fun account(profileId: Long, serverId: Long): ProfileAccountEntity?

    /** Profiles with no Jellyfin user of their own anywhere: they are the servers' own sign-ins. */
    @Query("SELECT * FROM profile WHERE id NOT IN (SELECT profileId FROM profile_account)")
    suspend fun withoutAccounts(): List<ProfileEntity>

    @Upsert
    suspend fun upsertAccount(account: ProfileAccountEntity)

    @Query("UPDATE profile_account SET userName = :userName WHERE profileId = :profileId AND serverId = :serverId")
    suspend fun setAccountName(profileId: Long, serverId: Long, userName: String)

    @Query("DELETE FROM profile_account WHERE profileId = :profileId AND serverId = :serverId")
    suspend fun deleteAccount(profileId: Long, serverId: Long)

    /** What the profile's own Jellyfin users can't see, on every server. */
    @Query("SELECT mediaItemId FROM profile_hidden_item WHERE profileId = :profileId")
    fun observeHidden(profileId: Long): Flow<List<Long>>

    @Query("DELETE FROM profile_hidden_item WHERE profileId = :profileId AND mediaItemId IN (SELECT id FROM media_item WHERE serverId = :serverId)")
    suspend fun clearHidden(profileId: Long, serverId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHidden(rows: List<HiddenItemEntity>)

    /** Replaces what the profile can't see on [serverId] with [itemIds]. */
    @Transaction
    suspend fun replaceHidden(profileId: Long, serverId: Long, itemIds: List<Long>) {
        clearHidden(profileId, serverId)
        insertHidden(itemIds.map { HiddenItemEntity(profileId, it) })
    }
}

@Dao
interface LiveTvDao {
    @Query("SELECT * FROM live_source WHERE profileId = :profileId ORDER BY name")
    fun observeSources(profileId: Long): Flow<List<LiveSourceEntity>>

    @Upsert
    suspend fun upsertSource(source: LiveSourceEntity): Long

    @Query("SELECT * FROM live_channel WHERE sourceId = :sourceId ORDER BY sortNumber")
    suspend fun channels(sourceId: Long): List<LiveChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannels(channels: List<LiveChannelEntity>)

    @Query(
        """
        SELECT * FROM live_program
        WHERE liveChannelId IN (:channelIds)
          AND startUtcMs > :fromUtcMs - :maxProgramMs AND startUtcMs < :toUtcMs
          AND endUtcMs > :fromUtcMs
        ORDER BY liveChannelId, startUtcMs
        """,
    )
    suspend fun programWindow(
        channelIds: List<Long>,
        fromUtcMs: Long,
        toUtcMs: Long,
        maxProgramMs: Long,
    ): List<LiveProgramEntity>

    @Insert
    suspend fun insertPrograms(programs: List<LiveProgramEntity>)

    @Query("DELETE FROM live_program WHERE endUtcMs < :beforeUtcMs")
    suspend fun deleteProgramsEndedBefore(beforeUtcMs: Long): Int
}

@Dao
interface ThemeDao {
    @Query("SELECT * FROM theme ORDER BY isBuiltIn DESC, name")
    fun observeAll(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM theme WHERE id = :id")
    suspend fun byId(id: Long): ThemeEntity?

    @Query("SELECT * FROM theme WHERE id = :id")
    fun observe(id: Long): Flow<ThemeEntity?>

    @Query("SELECT * FROM theme WHERE builtInKey = :key")
    suspend fun byBuiltInKey(key: String): ThemeEntity?

    @Query("SELECT * FROM theme WHERE isBuiltIn = 1")
    suspend fun builtIns(): List<ThemeEntity>

    @Query("SELECT name FROM theme")
    suspend fun names(): List<String>

    @Upsert
    suspend fun upsert(theme: ThemeEntity): Long

    @Query("DELETE FROM theme WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface WatchEventDao {
    @Insert
    suspend fun insert(event: WatchEventEntity): Long

    @Query("SELECT * FROM watch_event WHERE profileId = :profileId AND startUtcMs >= :sinceUtcMs ORDER BY startUtcMs DESC")
    suspend fun since(profileId: Long, sinceUtcMs: Long): List<WatchEventEntity>
}

@Dao
interface PairedClientDao {
    @Query("SELECT * FROM paired_client ORDER BY lastSeenUtcMs DESC")
    fun observeAll(): Flow<List<PairedClientEntity>>

    @Query("SELECT * FROM paired_client WHERE tokenHash = :tokenHash")
    suspend fun byTokenHash(tokenHash: String): PairedClientEntity?

    @Insert
    suspend fun insert(client: PairedClientEntity): Long

    @Query("UPDATE paired_client SET lastSeenUtcMs = :seenUtcMs, lastAddress = :address WHERE id = :id")
    suspend fun touch(id: Long, seenUtcMs: Long, address: String?)

    @Query("DELETE FROM paired_client WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM setting WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(setting: SettingEntity)

    @Query("DELETE FROM setting WHERE `key` = :key")
    suspend fun remove(key: String)

    /** Removes every per-profile setting of [profileId]: their keys all end in `.<profileId>`. */
    @Query("DELETE FROM setting WHERE `key` LIKE '%.' || :profileId")
    suspend fun removeForProfile(profileId: Long)
}
