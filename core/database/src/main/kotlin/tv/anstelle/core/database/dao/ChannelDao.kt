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
import tv.anstelle.core.database.entity.FillerRuleEntity

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel WHERE profileId = :profileId ORDER BY number")
    fun observeAll(profileId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE profileId = :profileId AND isHidden = 0 ORDER BY number")
    fun observeVisible(profileId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE profileId = :profileId AND number = :number LIMIT 1")
    suspend fun byNumber(profileId: Long, number: Int): ChannelEntity?

    @Query("SELECT COALESCE(MAX(number), 0) FROM channel WHERE profileId = :profileId")
    suspend fun maxNumber(profileId: Long): Int

    @Insert
    suspend fun insert(channel: ChannelEntity): Long

    @Update
    suspend fun update(channel: ChannelEntity)

    @Delete
    suspend fun delete(channel: ChannelEntity)

    @Query("UPDATE channel SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE channel SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("SELECT * FROM channel_rule WHERE channelId = :channelId ORDER BY logicGroup, id")
    suspend fun rules(channelId: Long): List<ChannelRuleEntity>

    @Query("DELETE FROM channel_rule WHERE channelId = :channelId")
    suspend fun deleteRules(channelId: Long)

    @Insert
    suspend fun insertRules(rules: List<ChannelRuleEntity>)

    @Transaction
    suspend fun replaceRules(channelId: Long, rules: List<ChannelRuleEntity>) {
        deleteRules(channelId)
        insertRules(rules)
    }

    @Query("SELECT * FROM channel_season WHERE channelId = :channelId")
    suspend fun seasons(channelId: Long): List<ChannelSeasonEntity>

    @Upsert
    suspend fun upsertSeason(season: ChannelSeasonEntity): Long

    @Query("SELECT * FROM channel_daypart WHERE channelId = :channelId ORDER BY startMinuteOfDay")
    suspend fun dayparts(channelId: Long): List<ChannelDaypartEntity>

    @Upsert
    suspend fun upsertDaypart(daypart: ChannelDaypartEntity): Long

    @Query("SELECT * FROM channel_group WHERE profileId = :profileId ORDER BY sortOrder")
    fun observeGroups(profileId: Long): Flow<List<ChannelGroupEntity>>

    @Upsert
    suspend fun upsertGroup(group: ChannelGroupEntity): Long

    @Delete
    suspend fun deleteGroup(group: ChannelGroupEntity)

    @Query(
        """
        SELECT * FROM filler_rule
        WHERE profileId = :profileId AND (channelId IS NULL OR channelId = :channelId)
        """,
    )
    suspend fun fillerRules(profileId: Long, channelId: Long): List<FillerRuleEntity>

    @Upsert
    suspend fun upsertFillerRule(rule: FillerRuleEntity): Long
}
