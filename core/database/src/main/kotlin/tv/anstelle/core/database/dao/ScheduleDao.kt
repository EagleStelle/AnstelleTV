package tv.anstelle.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.database.entity.ScheduleStateEntity

/** Guide-cell projection of a program row. */
data class GuideAiring(
    val channelId: Long,
    val mediaItemId: Long,
    val startUtcMs: Long,
    val endUtcMs: Long,
)

@Dao
interface ScheduleDao {
    /**
     * The slot airing on [channelId] at [nowUtcMs]. This is the whole tuning lookup:
     * one range seek on the (channelId, startUtcMs) index, no server call.
     */
    @Query(
        """
        SELECT * FROM schedule_slot
        WHERE channelId = :channelId AND startUtcMs <= :nowUtcMs
        ORDER BY startUtcMs DESC
        LIMIT 1
        """,
    )
    suspend fun slotAt(channelId: Long, nowUtcMs: Long): ScheduleSlotEntity?

    /** Slots following [afterUtcMs] on a channel, used to queue the next items in the player. */
    @Query(
        """
        SELECT * FROM schedule_slot
        WHERE channelId = :channelId AND startUtcMs >= :afterUtcMs
        ORDER BY startUtcMs
        LIMIT :limit
        """,
    )
    suspend fun slotsFrom(channelId: Long, afterUtcMs: Long, limit: Int): List<ScheduleSlotEntity>

    /**
     * Program cells overlapping `[fromUtcMs, toUtcMs)` for the visible channels.
     * [maxSlotMs] bounds the index range scan from below (`startUtcMs > from - maxSlot`)
     * so SQLite never walks the channel's history.
     */
    @Query(
        """
        SELECT channelId, mediaItemId, startUtcMs, endUtcMs
        FROM schedule_slot
        WHERE channelId IN (:channelIds)
          AND kind = 0
          AND startUtcMs > :fromUtcMs - :maxSlotMs AND startUtcMs < :toUtcMs
          AND endUtcMs > :fromUtcMs
        ORDER BY channelId, startUtcMs
        """,
    )
    suspend fun guideWindow(
        channelIds: List<Long>,
        fromUtcMs: Long,
        toUtcMs: Long,
        maxSlotMs: Long,
    ): List<GuideAiring>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlots(slots: List<ScheduleSlotEntity>)

    @Query("DELETE FROM schedule_slot WHERE channelId = :channelId AND startUtcMs >= :fromUtcMs")
    suspend fun deleteFrom(channelId: Long, fromUtcMs: Long)

    @Query("DELETE FROM schedule_slot WHERE endUtcMs < :beforeUtcMs")
    suspend fun deleteEndedBefore(beforeUtcMs: Long): Int

    @Query("SELECT * FROM schedule_state WHERE channelId = :channelId")
    suspend fun state(channelId: Long): ScheduleStateEntity?

    @Upsert
    suspend fun upsertState(state: ScheduleStateEntity)

    /**
     * Marks the schedules of channels with future slots airing any of [mediaItemIds] as
     * stale. A zero inputs hash never matches, so the extension worker regenerates them
     * after the current and next airings.
     */
    @Query(
        """
        UPDATE schedule_state SET inputsHash = 0
        WHERE channelId IN (
            SELECT DISTINCT channelId FROM schedule_slot
            WHERE mediaItemId IN (:mediaItemIds) AND endUtcMs > :nowUtcMs
        )
        """,
    )
    suspend fun invalidateForItems(mediaItemIds: List<Long>, nowUtcMs: Long): Int

    @Query("SELECT MAX(generatedUntilUtcMs) FROM schedule_state")
    fun observeGeneratedUntil(): Flow<Long?>

    /** Replaces everything from [fromUtcMs] on and stores the new engine state, atomically. */
    @Transaction
    suspend fun replaceFrom(
        channelId: Long,
        fromUtcMs: Long,
        slots: List<ScheduleSlotEntity>,
        state: ScheduleStateEntity,
    ) {
        deleteFrom(channelId, fromUtcMs)
        insertSlots(slots)
        upsertState(state)
    }
}
