package tv.anstelle.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.database.entity.ChannelDaypartEntity
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelGroupEntity
import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ProfileAccountEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.database.entity.RestoredItemEntity
import tv.anstelle.core.database.entity.SettingEntity
import tv.anstelle.core.database.entity.ThemeEntity
import tv.anstelle.core.database.entity.WatchEventEntity

/** A synced item as a backup names it: by its server and its Jellyfin id. */
data class ItemRef(val id: Long, val serverId: Long, val remoteId: String)

/** What a backup of this device would hold, counted. */
data class BackupCounts(
    val profiles: Int,
    val channels: Int,
    val customChannels: Int,
    /** Not `groups`, which SQL reserves. */
    val channelGroups: Int,
    val themes: Int,
    val servers: Int,
)

/**
 * Reads every table a backup holds and writes them back. A restore replaces everything in one
 * transaction ([replace]); what the library sync rebuilds (items, schedules, hidden items) is
 * left out of a backup.
 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM media_server ORDER BY id")
    suspend fun servers(): List<MediaServerEntity>

    @Query("SELECT * FROM library ORDER BY id")
    suspend fun libraries(): List<LibraryEntity>

    @Query("SELECT * FROM profile ORDER BY id")
    suspend fun profiles(): List<ProfileEntity>

    @Query("SELECT * FROM profile_account ORDER BY profileId, serverId")
    suspend fun accounts(): List<ProfileAccountEntity>

    @Query("SELECT * FROM theme ORDER BY id")
    suspend fun themes(): List<ThemeEntity>

    @Query("SELECT * FROM channel_group ORDER BY id")
    suspend fun groups(): List<ChannelGroupEntity>

    @Query("SELECT * FROM channel ORDER BY id")
    suspend fun channels(): List<ChannelEntity>

    @Query("SELECT * FROM channel_rule ORDER BY channelId, seasonId, logicGroup, id")
    suspend fun rules(): List<ChannelRuleEntity>

    @Query("SELECT * FROM channel_season ORDER BY id")
    suspend fun seasons(): List<ChannelSeasonEntity>

    @Query("SELECT * FROM channel_daypart ORDER BY id")
    suspend fun dayparts(): List<ChannelDaypartEntity>

    @Query("SELECT * FROM setting ORDER BY `key`")
    suspend fun settings(): List<SettingEntity>

    @Query("SELECT * FROM watch_event WHERE profileId IN (:profileIds) ORDER BY id")
    suspend fun history(profileIds: List<Long>): List<WatchEventEntity>

    /** The items the dashboards name, by their Jellyfin ids. */
    @Query("SELECT id, serverId, remoteId FROM media_item WHERE id IN (SELECT mediaItemId FROM watch_event WHERE mediaItemId IS NOT NULL)")
    suspend fun watchedItems(): List<ItemRef>

    /** Dashboard rows restored before and still waiting for their server's sync. */
    @Query("SELECT * FROM restored_item")
    suspend fun restoredItems(): List<RestoredItemEntity>

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM profile) AS profiles,
               (SELECT COUNT(*) FROM channel) AS channels,
               (SELECT COUNT(*) FROM channel WHERE autoKey IS NULL) AS customChannels,
               (SELECT COUNT(*) FROM channel_group) AS channelGroups,
               (SELECT COUNT(*) FROM theme WHERE isBuiltIn = 0) AS themes,
               (SELECT COUNT(*) FROM media_server) AS servers
        """,
    )
    fun observeCounts(): Flow<BackupCounts>

    /** Days the dashboards hold, leaving out the profiles that turned theirs off (`dashboard.<id>` false). */
    @Query(
        """
        SELECT COUNT(DISTINCT w.localDay) FROM watch_event w
        WHERE NOT EXISTS (SELECT 1 FROM setting s WHERE s.`key` = 'dashboard.' || w.profileId AND s.value = 'false')
        """,
    )
    fun observeHistoryDays(): Flow<Int>

    @Insert suspend fun insertServers(rows: List<MediaServerEntity>)
    @Insert suspend fun insertLibraries(rows: List<LibraryEntity>)
    @Insert suspend fun insertProfiles(rows: List<ProfileEntity>)
    @Insert suspend fun insertAccounts(rows: List<ProfileAccountEntity>)
    @Insert suspend fun insertTheme(row: ThemeEntity): Long
    @Insert suspend fun insertGroups(rows: List<ChannelGroupEntity>)
    @Insert suspend fun insertChannels(rows: List<ChannelEntity>)
    @Insert suspend fun insertSeasons(rows: List<ChannelSeasonEntity>)
    @Insert suspend fun insertRules(rows: List<ChannelRuleEntity>)
    @Insert suspend fun insertDayparts(rows: List<ChannelDaypartEntity>)
    @Upsert suspend fun putSettings(rows: List<SettingEntity>)

    /** Returns the new rows' ids, in order. */
    @Insert suspend fun insertHistory(rows: List<WatchEventEntity>): List<Long>

    @Insert suspend fun insertRestoredItems(rows: List<RestoredItemEntity>)

    @Query("DELETE FROM schedule_slot") suspend fun clearSlots()
    @Query("DELETE FROM schedule_state") suspend fun clearStates()
    @Query("DELETE FROM channel_rule") suspend fun clearRules()
    @Query("DELETE FROM channel_season") suspend fun clearSeasons()
    @Query("DELETE FROM channel_daypart") suspend fun clearDayparts()
    @Query("DELETE FROM channel") suspend fun clearChannels()
    @Query("DELETE FROM channel_group") suspend fun clearGroups()
    @Query("DELETE FROM restored_item") suspend fun clearRestored()
    @Query("DELETE FROM watch_event") suspend fun clearHistory()
    @Query("DELETE FROM profile_hidden_item") suspend fun clearHidden()
    @Query("DELETE FROM profile_account") suspend fun clearAccounts()
    @Query("DELETE FROM live_program") suspend fun clearLivePrograms()
    @Query("DELETE FROM live_channel") suspend fun clearLiveChannels()
    @Query("DELETE FROM live_source") suspend fun clearLiveSources()
    @Query("DELETE FROM profile") suspend fun clearProfiles()
    @Query("DELETE FROM media_item") suspend fun clearItems()
    @Query("DELETE FROM library") suspend fun clearLibraries()
    @Query("DELETE FROM media_server") suspend fun clearServers()
    @Query("DELETE FROM theme WHERE isBuiltIn = 0") suspend fun clearCustomThemes()

    /** Every setting but the device's own ([keep]). */
    @Query("DELETE FROM setting WHERE `key` NOT IN (:keep)") suspend fun clearSettings(keep: List<String>)

    /**
     * Empties every table a backup replaces, children before their parents. Built-in themes stay;
     * paired browsers go with the profiles that paired them.
     */
    @Transaction
    suspend fun clear(keepSettings: List<String>) {
        clearSlots()
        clearStates()
        clearRules()
        clearSeasons()
        clearDayparts()
        clearChannels()
        clearGroups()
        clearRestored()
        clearHistory()
        clearHidden()
        clearAccounts()
        clearLivePrograms()
        clearLiveChannels()
        clearLiveSources()
        clearProfiles()
        clearItems()
        clearLibraries()
        clearServers()
        clearCustomThemes()
        clearSettings(keepSettings)
    }

    /** Points the restored dashboard rows of [serverId] at their items again, now that the server has synced. */
    @Query(
        """
        UPDATE watch_event SET mediaItemId = (
            SELECT m.id FROM restored_item r JOIN media_item m ON m.serverId = r.serverId AND m.remoteId = r.remoteId
            WHERE r.eventId = watch_event.id
        )
        WHERE id IN (SELECT eventId FROM restored_item WHERE serverId = :serverId)
        """,
    )
    suspend fun linkRestoredItems(serverId: Long): Int

    @Query("DELETE FROM restored_item WHERE serverId = :serverId")
    suspend fun clearRestoredItems(serverId: Long)

    /** Links the restored rows of [serverId] and forgets them; an item that isn't synced any more leaves its row without one. */
    @Transaction
    suspend fun settleRestoredItems(serverId: Long): Int {
        val linked = linkRestoredItems(serverId)
        clearRestoredItems(serverId)
        return linked
    }
}
