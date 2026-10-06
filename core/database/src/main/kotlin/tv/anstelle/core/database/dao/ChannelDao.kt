package tv.anstelle.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.database.entity.ChannelDaypartEntity
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelGroupEntity
import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel WHERE profileId = :profileId ORDER BY number")
    fun observeAll(profileId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE profileId = :profileId AND isHidden = 0 ORDER BY number")
    fun observeVisible(profileId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE id = :id")
    fun observe(id: Long): Flow<ChannelEntity?>

    @Query("SELECT * FROM channel ORDER BY profileId, number")
    suspend fun all(): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE profileId = :profileId ORDER BY number")
    suspend fun forProfile(profileId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE profileId = :profileId AND number = :number LIMIT 1")
    suspend fun byNumber(profileId: Long, number: Int): ChannelEntity?

    @Query("SELECT COALESCE(MAX(number), 0) FROM channel WHERE profileId = :profileId")
    suspend fun maxNumber(profileId: Long): Int

    @Query("SELECT templateId FROM channel WHERE profileId = :profileId AND templateId IS NOT NULL")
    suspend fun templateIds(profileId: Long): List<String>

    @Insert
    suspend fun insert(channel: ChannelEntity): Long

    @Update
    suspend fun update(channel: ChannelEntity)

    @Delete
    suspend fun delete(channel: ChannelEntity)

    @Query("UPDATE channel SET isFavorite = :favorite, updatedAtUtcMs = :atUtcMs WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, atUtcMs: Long)

    @Query("UPDATE channel SET isHidden = :hidden, updatedAtUtcMs = :atUtcMs WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean, atUtcMs: Long)

    @Query("UPDATE channel SET groupId = :groupId, updatedAtUtcMs = :atUtcMs WHERE id = :id")
    suspend fun setGroup(id: Long, groupId: Long?, atUtcMs: Long)

    @Query("UPDATE channel SET logoUri = :logoUri, updatedAtUtcMs = :atUtcMs WHERE id = :id")
    suspend fun setLogo(id: Long, logoUri: String?, atUtcMs: Long)

    @Query("UPDATE channel SET number = :number, updatedAtUtcMs = :atUtcMs WHERE id = :id")
    suspend fun setNumber(id: Long, number: Int, atUtcMs: Long)

    /**
     * Gives [channel] [number]. A channel of the same profile that already has the number
     * takes [channel]'s old number, so numbers stay unique.
     */
    @Transaction
    suspend fun renumber(channel: ChannelEntity, number: Int, atUtcMs: Long) {
        byNumber(channel.profileId, number)?.let { other ->
            if (other.id != channel.id) setNumber(other.id, channel.number, atUtcMs)
        }
        setNumber(channel.id, number, atUtcMs)
    }

    /** All rule rows of a channel: its own, its seasons' and its dayparts'. */
    @Query("SELECT * FROM channel_rule WHERE channelId = :channelId ORDER BY seasonId, logicGroup, id")
    suspend fun rules(channelId: Long): List<ChannelRuleEntity>

    @Query("DELETE FROM channel_rule WHERE channelId = :channelId AND seasonId IS NULL AND logicGroup NOT IN (:keepGroups)")
    suspend fun deleteBaseRules(channelId: Long, keepGroups: List<Int>)

    @Insert
    suspend fun insertRules(rules: List<ChannelRuleEntity>)

    /** Replaces the channel's base rules, keeping season rules and the groups in [keepGroups] (used by dayparts). */
    @Transaction
    suspend fun replaceBaseRules(channelId: Long, rules: List<ChannelRuleEntity>, keepGroups: List<Int>) {
        deleteBaseRules(channelId, keepGroups)
        insertRules(rules)
    }

    @Query("SELECT * FROM channel_season WHERE channelId = :channelId ORDER BY id")
    suspend fun seasons(channelId: Long): List<ChannelSeasonEntity>

    @Query("SELECT * FROM channel_season WHERE channelId IN (:channelIds)")
    suspend fun seasonsFor(channelIds: List<Long>): List<ChannelSeasonEntity>

    @Insert
    suspend fun insertSeason(season: ChannelSeasonEntity): Long

    @Upsert
    suspend fun upsertSeason(season: ChannelSeasonEntity): Long

    @Query("SELECT * FROM channel_daypart WHERE channelId = :channelId ORDER BY startMinuteOfDay")
    suspend fun dayparts(channelId: Long): List<ChannelDaypartEntity>

    @Upsert
    suspend fun upsertDaypart(daypart: ChannelDaypartEntity): Long

    @Query("SELECT * FROM channel_group WHERE profileId = :profileId ORDER BY sortOrder, id")
    fun observeGroups(profileId: Long): Flow<List<ChannelGroupEntity>>

    @Query("SELECT * FROM channel_group WHERE profileId = :profileId ORDER BY sortOrder, id")
    suspend fun groups(profileId: Long): List<ChannelGroupEntity>

    @Query("SELECT * FROM channel_group WHERE id = :id")
    suspend fun groupById(id: Long): ChannelGroupEntity?

    @Insert
    suspend fun insertGroup(group: ChannelGroupEntity): Long

    @Upsert
    suspend fun upsertGroup(group: ChannelGroupEntity): Long

    @Update
    suspend fun updateGroups(groups: List<ChannelGroupEntity>)

    @Delete
    suspend fun deleteGroup(group: ChannelGroupEntity)
}
