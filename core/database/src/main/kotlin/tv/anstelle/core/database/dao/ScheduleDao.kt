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
import tv.anstelle.core.model.SlotKind

/** Guide-cell projection of a slot, with what the cell shows. The item fields are null off air. */
data class GuideAiring(
    val channelId: Long,
    val kind: SlotKind,
    val mediaItemId: Long?,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val title: String?,
    val showTitle: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
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

    /** Media items of the programs starting at or after [fromUtcMs], in airing order. */
    @Query(
        """
        SELECT mediaItemId FROM schedule_slot
        WHERE channelId = :channelId AND startUtcMs >= :fromUtcMs AND kind = 0
        ORDER BY startUtcMs
        """,
    )
    suspend fun programItemIdsFrom(channelId: Long, fromUtcMs: Long): List<Long>

    /**
     * Guide cells (programs and off-air stretches) overlapping `[fromUtcMs, toUtcMs)` for
     * [channelIds]. [maxSlotMs] bounds the index range scan from below
     * (`startUtcMs > from - maxSlot`) so SQLite never walks the channel's history.
     */
    @Query(
        """
        SELECT s.channelId, s.kind, s.mediaItemId, s.startUtcMs, s.endUtcMs,
               m.title, m.showTitle, m.season, m.episode, m.year
        FROM schedule_slot s LEFT JOIN media_item m ON m.id = s.mediaItemId
        WHERE s.channelId IN (:channelIds)
          AND s.startUtcMs > :fromUtcMs - :maxSlotMs AND s.startUtcMs < :toUtcMs
          AND s.endUtcMs > :fromUtcMs
        ORDER BY s.channelId, s.startUtcMs
        """,
    )
    suspend fun guideWindow(
        channelIds: List<Long>,
        fromUtcMs: Long,
        toUtcMs: Long,
        maxSlotMs: Long,
    ): List<GuideAiring>

    /** The longest slot, which bounds [guideWindow]'s range scan. */
    @Query("SELECT COALESCE(MAX(endUtcMs - startUtcMs), 0) FROM schedule_slot")
    suspend fun maxSlotMs(): Long

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
