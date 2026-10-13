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
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.WatchSource

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

/** One row's time on its day, for the dashboard's charts and habits. */
data class ViewTime(
    val startUtcMs: Long,
    val durationMs: Long,
    val localDay: Long,
    val source: WatchSource,
    val completed: Boolean,
    val mediaType: MediaType,
)

/** How long the profile watched on [localDay]. */
data class DayTotal(val localDay: Long, val playedMs: Long)

/** A dashboard list entry: what it is, the time watched, and the item watched last for its artwork. */
data class TopRow(
    val title: String,
    val playedMs: Long,
    val itemId: Long?,
    val lastUtcMs: Long,
    /** A channel's number; null for anything else. */
    val number: Int? = null,
)

/** The time one item was watched, with its genres (`|Comedy|Drama|`) while it is still synced. */
data class GenreRow(val itemId: Long, val genres: String, val playedMs: Long)

/** An item's artwork tags, with its series' and its server's address. */
data class ArtworkRow(
    val id: Long,
    val baseUrl: String,
    val remoteId: String,
    val backdropTag: String?,
    val logoTag: String?,
    val seriesRemoteId: String?,
    val seriesBackdropTag: String?,
    val seriesLogoTag: String?,
)

/**
 * The time a row played between [fromUtcMs] and [toUtcMs]: all of it when the bounds are open, a
 * clipped share when the dashboard looks at one hour.
 */
private const val PLAYED = "MAX(0, MIN(w.startUtcMs + w.durationMs, :toUtcMs) - MAX(w.startUtcMs, :fromUtcMs))"

/** The profile's rows on [fromDay]..[toDay] that played between [fromUtcMs] and [toUtcMs]. */
private const val IN_RANGE = """
    w.profileId = :profileId AND w.localDay BETWEEN :fromDay AND :toDay
      AND w.startUtcMs < :toUtcMs AND w.startUtcMs + w.durationMs > :fromUtcMs
"""

/**
 * The dashboard's history. Every read is one profile's indexed `localDay` range. In the list
 * queries a bare `mediaItemId` beside `MAX(startUtcMs)` is SQLite's value from the row watched
 * last, whose artwork the entry shows.
 */
@Dao
interface WatchEventDao {
    @Insert
    suspend fun insertAll(events: List<WatchEventEntity>)

    @Query("SELECT startUtcMs, durationMs, localDay, source, completed, mediaType FROM watch_event WHERE profileId = :profileId AND localDay BETWEEN :fromDay AND :toDay ORDER BY startUtcMs")
    suspend fun views(profileId: Long, fromDay: Long, toDay: Long): List<ViewTime>

    /** Every day the profile watched something, oldest first. */
    @Query("SELECT DISTINCT localDay FROM watch_event WHERE profileId = :profileId ORDER BY localDay")
    suspend fun watchedDays(profileId: Long): List<Long>

    @Query("SELECT localDay, SUM(durationMs) AS playedMs FROM watch_event WHERE profileId = :profileId AND localDay BETWEEN :fromDay AND :toDay GROUP BY localDay")
    suspend fun dayTotals(profileId: Long, fromDay: Long, toDay: Long): List<DayTotal>

    @Query("SELECT COALESCE(SUM($PLAYED), 0) FROM watch_event w WHERE $IN_RANGE")
    suspend fun playedTotal(profileId: Long, fromDay: Long, toDay: Long, fromUtcMs: Long, toUtcMs: Long): Long

    /** Channels that still exist, most watched first. */
    @Query(
        """
        SELECT c.name AS title, SUM($PLAYED) AS playedMs, w.mediaItemId AS itemId, MAX(w.startUtcMs) AS lastUtcMs, c.number AS number
        FROM watch_event w JOIN channel c ON c.id = w.channelId
        WHERE $IN_RANGE
        GROUP BY w.channelId ORDER BY playedMs DESC LIMIT :limit
        """,
    )
    suspend fun topChannels(profileId: Long, fromDay: Long, toDay: Long, fromUtcMs: Long, toUtcMs: Long, limit: Int): List<TopRow>

    /** Series by their title, most watched first. */
    @Query(
        """
        SELECT w.showTitle AS title, SUM($PLAYED) AS playedMs, w.mediaItemId AS itemId, MAX(w.startUtcMs) AS lastUtcMs, NULL AS number
        FROM watch_event w
        WHERE $IN_RANGE AND w.mediaType = :episode AND w.showTitle IS NOT NULL
        GROUP BY w.showTitle ORDER BY playedMs DESC LIMIT :limit
        """,
    )
    suspend fun topSeries(profileId: Long, fromDay: Long, toDay: Long, fromUtcMs: Long, toUtcMs: Long, limit: Int, episode: MediaType): List<TopRow>

    /** Movies by their title, most watched first. */
    @Query(
        """
        SELECT w.title AS title, SUM($PLAYED) AS playedMs, w.mediaItemId AS itemId, MAX(w.startUtcMs) AS lastUtcMs, NULL AS number
        FROM watch_event w
        WHERE $IN_RANGE AND w.mediaType = :movie
        GROUP BY w.title ORDER BY playedMs DESC LIMIT :limit
        """,
    )
    suspend fun topMovies(profileId: Long, fromDay: Long, toDay: Long, fromUtcMs: Long, toUtcMs: Long, limit: Int, movie: MediaType): List<TopRow>

    /**
     * Each item watched that is still in the library (its title must match, since a removed
     * item's id can be taken again), with its genres.
     */
    @Query(
        """
        SELECT w.mediaItemId AS itemId, m.genres AS genres, SUM($PLAYED) AS playedMs
        FROM watch_event w JOIN media_item m ON m.id = w.mediaItemId AND m.title = w.title
        WHERE $IN_RANGE
        GROUP BY w.mediaItemId
        """,
    )
    suspend fun genreRows(profileId: Long, fromDay: Long, toDay: Long, fromUtcMs: Long, toUtcMs: Long): List<GenreRow>

    @Query(
        """
        SELECT m.id AS id, s.baseUrl AS baseUrl, m.remoteId AS remoteId, m.backdropImageTag AS backdropTag,
               m.logoImageTag AS logoTag, m.seriesRemoteId AS seriesRemoteId,
               p.backdropImageTag AS seriesBackdropTag, p.logoImageTag AS seriesLogoTag
        FROM media_item m JOIN media_server s ON s.id = m.serverId
        LEFT JOIN media_item p ON p.serverId = m.serverId AND p.remoteId = m.seriesRemoteId
        WHERE m.id IN (:ids)
        """,
    )
    suspend fun artwork(ids: List<Long>): List<ArtworkRow>

    @Query("DELETE FROM watch_event WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: Long)

    /** Drops every profile's rows from before [beforeDay]; the `IN` keeps the delete on the index. */
    @Query("DELETE FROM watch_event WHERE profileId IN (SELECT id FROM profile) AND localDay < :beforeDay")
    suspend fun deleteBefore(beforeDay: Long): Int
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

    @Query("DELETE FROM paired_client WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: Long)
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
