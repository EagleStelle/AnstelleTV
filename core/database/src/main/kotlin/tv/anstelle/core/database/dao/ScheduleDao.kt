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
    /** The episode's series, or the item itself when it has none. */
    val showId: Long?,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val title: String?,
    val showTitle: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    /** The item's rating score, which a profile's maturity rating limits; null when unrated. */
    val ratingScore: Int?,
)

/**
 * What a channel airs at one moment, with the artwork tags of the item and of its series. The
 * item fields are null off air.
 */
data class ChannelAiring(
    val channelId: Long,
    val mediaItemId: Long?,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val serverId: Long?,
    val remoteId: String?,
    val title: String?,
    val showTitle: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val overview: String?,
    val ratingScore: Int?,
    val backdropImageTag: String?,
    val logoImageTag: String?,
    val seriesRemoteId: String?,
    val seriesBackdropImageTag: String?,
    val seriesLogoImageTag: String?,
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
        SELECT s.channelId, s.kind, s.mediaItemId, COALESCE(series.id, m.id) AS showId,
               s.startUtcMs, s.endUtcMs, m.title, m.showTitle, m.season, m.episode, m.year, m.ratingScore
        FROM schedule_slot s LEFT JOIN media_item m ON m.id = s.mediaItemId
        LEFT JOIN media_item series ON series.serverId = m.serverId AND series.remoteId = m.seriesRemoteId
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

    /**
     * What each of [channelIds] airs at [atUtcMs], programs and off-air stretches alike, in one
     * read. [maxSlotMs] bounds the index range scan like [guideWindow]'s.
     */
    @Query(
        """
        SELECT s.channelId, s.mediaItemId, s.startUtcMs, s.endUtcMs, m.serverId, m.remoteId,
               m.title, m.showTitle, m.season, m.episode, m.year, m.overview, m.ratingScore,
               m.backdropImageTag, m.logoImageTag, series.remoteId AS seriesRemoteId,
               series.backdropImageTag AS seriesBackdropImageTag, series.logoImageTag AS seriesLogoImageTag
        FROM schedule_slot s LEFT JOIN media_item m ON m.id = s.mediaItemId
        LEFT JOIN media_item series ON series.serverId = m.serverId AND series.remoteId = m.seriesRemoteId
        WHERE s.channelId IN (:channelIds)
          AND s.startUtcMs > :atUtcMs - :maxSlotMs AND s.startUtcMs <= :atUtcMs
          AND s.endUtcMs > :atUtcMs
        """,
    )
    suspend fun airingsAt(channelIds: List<Long>, atUtcMs: Long, maxSlotMs: Long): List<ChannelAiring>

    /**
     * Where a run of programs profile [profileId] may not watch ends on [channelId], from
     * [fromUtcMs] (the end of the restricted program on now): the start of the first slot from
     * there that is off air, or rated within [maxRating] (null: no limit) and not hidden from the
     * profile, else the end of the schedule. Unrated programs never pass a limit. Null when the
     * channel has no schedule.
     */
    @Query(
        """
        SELECT COALESCE(
            (SELECT MIN(s.startUtcMs) FROM schedule_slot s LEFT JOIN media_item m ON m.id = s.mediaItemId
             WHERE s.channelId = :channelId AND s.startUtcMs >= :fromUtcMs
               AND (s.mediaItemId IS NULL OR (
                   (:maxRating IS NULL OR (m.ratingScore IS NOT NULL AND m.ratingScore <= :maxRating))
                   AND NOT EXISTS (SELECT 1 FROM profile_hidden_item h WHERE h.profileId = :profileId AND h.mediaItemId = s.mediaItemId)))),
            (SELECT MAX(endUtcMs) FROM schedule_slot WHERE channelId = :channelId))
        """,
    )
    suspend fun restrictedUntil(channelId: Long, fromUtcMs: Long, maxRating: Int?, profileId: Long): Long?

    /**
     * Channels whose every program from [nowUtcMs] on is hidden from profile [profileId]: what
     * they air comes from libraries the profile's Jellyfin users can't see.
     */
    @Query(
        """
        SELECT s.channelId FROM schedule_slot s
        LEFT JOIN profile_hidden_item h ON h.profileId = :profileId AND h.mediaItemId = s.mediaItemId
        WHERE s.endUtcMs > :nowUtcMs AND s.mediaItemId IS NOT NULL
        GROUP BY s.channelId
        HAVING COUNT(h.mediaItemId) = COUNT(*)
        """,
    )
    suspend fun hiddenChannels(profileId: Long, nowUtcMs: Long): List<Long>

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

    @Query("SELECT * FROM schedule_slot WHERE channelId = :channelId AND endUtcMs = :endUtcMs LIMIT 1")
    suspend fun slotEndingAt(channelId: Long, endUtcMs: Long): ScheduleSlotEntity?

    @Query("UPDATE schedule_slot SET endUtcMs = :endUtcMs WHERE id = :id")
    suspend fun setEnd(id: Long, endUtcMs: Long)

    @Query("DELETE FROM schedule_slot WHERE channelId = :channelId AND startUtcMs >= :fromUtcMs AND startUtcMs < :toUtcMs")
    suspend fun deleteBetween(channelId: Long, fromUtcMs: Long, toUtcMs: Long)

    /** Ends the slot running across [atUtcMs] there. */
    @Query("UPDATE schedule_slot SET endUtcMs = :atUtcMs WHERE channelId = :channelId AND startUtcMs < :atUtcMs AND endUtcMs > :atUtcMs")
    suspend fun cutAt(channelId: Long, atUtcMs: Long)

    /**
     * First half of moving the slots from [fromUtcMs] on by [deltaMs]: their starts go negative
     * (and moved), so no row lands on another's start while the unique index is checked row by
     * row. [moveIn] flips them back.
     */
    @Query(
        """
        UPDATE schedule_slot SET startUtcMs = -(startUtcMs + :deltaMs) - 1, endUtcMs = endUtcMs + :deltaMs
        WHERE channelId = :channelId AND startUtcMs >= :fromUtcMs
        """,
    )
    suspend fun moveOut(channelId: Long, fromUtcMs: Long, deltaMs: Long)

    @Query("UPDATE schedule_slot SET startUtcMs = -startUtcMs - 1 WHERE channelId = :channelId AND startUtcMs < 0")
    suspend fun moveIn(channelId: Long)

    /**
     * Re-times [channelId] after a seek: the airing that started at [startUtcMs] starts
     * [deltaMs] later (a seek back) or earlier (a seek ahead), and every later slot moves the
     * same way. Earlier airings it now overlaps are cut short; when it moves later, the part
     * already shown stays in the history as an airing of its own (merged with one left by an
     * earlier seek). [restate] moves the engine state to match.
     */
    @Transaction
    suspend fun shift(channelId: Long, startUtcMs: Long, deltaMs: Long, restate: (ScheduleStateEntity) -> ScheduleStateEntity) {
        if (deltaMs == 0L) return
        val newStart = startUtcMs + deltaMs
        if (deltaMs < 0) {
            deleteBetween(channelId, newStart, startUtcMs)
            cutAt(channelId, newStart)
        }
        moveOut(channelId, startUtcMs, deltaMs)
        moveIn(channelId)
        if (deltaMs > 0) {
            val moved = slotAt(channelId, newStart)?.takeIf { it.startUtcMs == newStart }
            val before = slotEndingAt(channelId, startUtcMs)
            when {
                moved == null -> Unit
                before != null && before.kind == moved.kind && before.mediaItemId == moved.mediaItemId -> setEnd(before.id, newStart)
                else -> insertSlots(listOf(moved.copy(id = 0, startUtcMs = startUtcMs, endUtcMs = newStart)))
            }
        }
        state(channelId)?.let { upsertState(restate(it)) }
    }

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
