package tv.anstelle.core.data.channels

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ScheduleRepository
import tv.anstelle.core.database.dao.ChannelDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.scheduler.ScheduleEngine
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What updating one channel's schedule did. */
enum class ScheduleOutcome {
    /** No stored schedule: generated from the start of the current hour. */
    CREATED,

    /** The inputs changed: regenerated after the current and next airings. */
    REGENERATED,

    /** Unchanged inputs: generated further ahead from the stored state. */
    EXTENDED,
    UP_TO_DATE,

    /** Hidden channels are not scheduled until they are shown again. */
    HIDDEN,
}

/**
 * Keeps every channel's schedule [HORIZON_MS] ahead.
 *
 * - **Extension** resumes from the stored engine state, so a schedule extended day by day is
 *   the same as one generated at once.
 * - **Regeneration:** when a channel's inputs change (rules, settings, matched items, zone)
 *   or sync marks it stale, the airing on now and the one after it stay; everything later is
 *   regenerated. An airing whose item was deleted, or an off-air stretch, is not kept.
 */
@Singleton
class ScheduleUpdater @Inject constructor(
    private val channelDao: ChannelDao,
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val schedules: ScheduleRepository,
    private val words: ChannelWordsSource,
    private val time: TimeSource,
) {
    private val mutex = Mutex()

    /** Updates every channel of every profile. */
    suspend fun updateAll(zone: ZoneId = ZoneId.systemDefault()): Map<ScheduleOutcome, Int> = mutex.withLock {
        val started = time.nowUtcMs()
        val channels = channelDao.all()
        if (channels.isEmpty()) return@withLock emptyMap()
        val catalog = Catalog(itemDao.catalog(), words.words().types)
        val outcomes = channels.map { channel -> updateLocked(channel, catalog, zone) }
        val summary = outcomes.groupingBy { it }.eachCount()
        AppLog.i(TAG, "${channels.size} channels in ${time.nowUtcMs() - started} ms: $summary")
        summary
    }

    /** Updates one channel, e.g. right after its rules changed. */
    suspend fun update(channelId: Long, zone: ZoneId = ZoneId.systemDefault()): ScheduleOutcome? = mutex.withLock {
        val channel = channelDao.byId(channelId) ?: return@withLock null
        updateLocked(channel, Catalog(itemDao.catalog(), words.words().types), zone)
    }

    private suspend fun updateLocked(channel: ChannelEntity, catalog: Catalog, zone: ZoneId): ScheduleOutcome {
        if (channel.isHidden) return ScheduleOutcome.HIDDEN
        val setup = ChannelSetup(channel, channelDao.rules(channel.id), channelDao.seasons(channel.id), channelDao.dayparts(channel.id))
        val inputs = ChannelInputs.build(setup, catalog, zone)
        val hash = inputs.fingerprint()
        val now = time.nowUtcMs()
        val horizon = now + HORIZON_MS
        val stored = schedules.stored(channel.id)

        when {
            stored == null -> {
                val start = hourStart(now, zone)
                val result = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), horizon)
                schedules.save(channel.id, start, result, hash)
                return ScheduleOutcome.CREATED
            }
            stored.inputsHash != hash -> {
                val cursor = keepUntil(channel.id, now, zone, catalog)
                val removed = scheduleDao.programItemIdsFrom(channel.id, cursor).toLongArray()
                val state = ScheduleEngine.rewind(inputs, stored.state, cursor, removed)
                schedules.save(channel.id, cursor, ScheduleEngine.generate(inputs, state, horizon), hash)
                return ScheduleOutcome.REGENERATED
            }
            stored.generatedUntilUtcMs < horizon - EXTEND_MARGIN_MS -> {
                var state = stored.state
                // A schedule that ran out while nobody watched restarts now instead of filling the past.
                if (state.nextStartUtcMs < now) state = state.copy(nextStartUtcMs = maxOf(state.nextStartUtcMs, hourStart(now, zone)))
                schedules.save(channel.id, state.nextStartUtcMs, ScheduleEngine.generate(inputs, state, horizon), hash)
                return ScheduleOutcome.EXTENDED
            }
            else -> return ScheduleOutcome.UP_TO_DATE
        }
    }

    /** Where regeneration starts: after the current and next airings, unless they can't stay. */
    private suspend fun keepUntil(channelId: Long, now: Long, zone: ZoneId, catalog: Catalog): Long {
        val current = scheduleDao.slotAt(channelId, now)?.takeIf { now < it.endUtcMs } ?: return hourStart(now, zone)
        if (!keeps(current.kind, current.mediaItemId, catalog)) return current.startUtcMs
        val next = scheduleDao.slotsFrom(channelId, current.endUtcMs, 1).firstOrNull() ?: return current.endUtcMs
        if (!keeps(next.kind, next.mediaItemId, catalog)) return next.startUtcMs
        return next.endUtcMs
    }

    private fun keeps(kind: SlotKind, mediaItemId: Long?, catalog: Catalog) =
        kind == SlotKind.PROGRAM && mediaItemId != null && catalog[mediaItemId] != null

    private fun hourStart(utcMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(utcMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    companion object {
        private const val TAG = "Schedules"
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** How far ahead schedules are generated. */
        const val HORIZON_MS = 30 * DAY_MS

        /** A schedule is only extended once it is this much short of the horizon. */
        const val EXTEND_MARGIN_MS = DAY_MS / 2
    }
}
