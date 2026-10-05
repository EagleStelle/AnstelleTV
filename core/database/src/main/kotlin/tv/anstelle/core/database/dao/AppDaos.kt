package tv.anstelle.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.database.entity.LiveChannelEntity
import tv.anstelle.core.database.entity.LiveProgramEntity
import tv.anstelle.core.database.entity.LiveSourceEntity
import tv.anstelle.core.database.entity.PairedClientEntity
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

    @Query("SELECT COUNT(*) FROM profile")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(profile: ProfileEntity): Long

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun delete(id: Long)
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

    @Query("SELECT * FROM theme WHERE builtInKey = :key")
    suspend fun byBuiltInKey(key: String): ThemeEntity?

    @Upsert
    suspend fun upsert(theme: ThemeEntity): Long
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
}
