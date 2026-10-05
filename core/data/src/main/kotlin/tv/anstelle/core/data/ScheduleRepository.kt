package tv.anstelle.core.data

import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.database.entity.ScheduleStateEntity
import tv.anstelle.core.model.NowPlaying
import tv.anstelle.core.scheduler.EngineState
import tv.anstelle.core.scheduler.ScheduleResult
import tv.anstelle.core.scheduler.ScheduleSlots
import tv.anstelle.core.scheduler.nowPlaying
import javax.inject.Inject
import javax.inject.Singleton

/** A channel's stored engine state and what it was generated from. */
data class StoredSchedule(
    val state: EngineState,
    val generatedUntilUtcMs: Long,
    /** 0 means stale: the inputs changed and the schedule must be regenerated. */
    val inputsHash: Long,
)

/** Stores generated schedules and answers "what is on now" from SQLite alone. */
@Singleton
class ScheduleRepository @Inject constructor(
    private val scheduleDao: ScheduleDao,
    private val time: TimeSource,
) {
    /** What [channelId] airs at [nowUtcMs]: one indexed query, no server call. */
    suspend fun nowPlaying(channelId: Long, nowUtcMs: Long = time.nowUtcMs()): NowPlaying? {
        val slot = scheduleDao.slotAt(channelId, nowUtcMs) ?: return null
        return nowPlaying(
            channelId = channelId,
            kind = slot.kind,
            mediaItemId = slot.mediaItemId,
            startUtcMs = slot.startUtcMs,
            endUtcMs = slot.endUtcMs,
            nowUtcMs = nowUtcMs,
        )
    }

    /** The channel's engine state, or null when it has none or the stored state can't be read. */
    suspend fun stored(channelId: Long): StoredSchedule? {
        val entity = scheduleDao.state(channelId) ?: return null
        val state = try {
            EngineState.decode(entity.stateBlob)
        } catch (e: Exception) {
            AppLog.w(TAG, "Unreadable schedule state for channel $channelId, regenerating", e)
            return null
        }
        return StoredSchedule(state, entity.generatedUntilUtcMs, entity.inputsHash)
    }

    /**
     * Replaces the channel's slots from [fromUtcMs] on with [result] and stores the new
     * engine state, in one transaction.
     */
    suspend fun save(channelId: Long, fromUtcMs: Long, result: ScheduleResult, inputsHash: Long) {
        val state = ScheduleStateEntity(
            channelId = channelId,
            generatedUntilUtcMs = result.state.nextStartUtcMs,
            stateBlob = result.state.encode(),
            inputsHash = inputsHash,
            updatedAtUtcMs = time.nowUtcMs(),
        )
        scheduleDao.replaceFrom(channelId, fromUtcMs, result.slots.toEntities(channelId), state)
    }

    private companion object {
        const val TAG = "Schedule"
    }
}

internal fun ScheduleSlots.toEntities(channelId: Long): List<ScheduleSlotEntity> = List(size) { i ->
    ScheduleSlotEntity(
        channelId = channelId,
        kind = kind(i),
        mediaItemId = mediaItemId(i),
        startUtcMs = startUtcMs(i),
        endUtcMs = endUtcMs(i),
    )
}
