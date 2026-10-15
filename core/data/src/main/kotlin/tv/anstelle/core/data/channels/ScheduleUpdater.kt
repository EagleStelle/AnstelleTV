package tv.anstelle.core.data.channels

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ScheduleRepository
import tv.anstelle.core.data.playback.MediaDurations
import tv.anstelle.core.data.playback.ProgramLength
import tv.anstelle.core.database.dao.ChannelDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.scheduler.PastAiring
import tv.anstelle.core.scheduler.ScheduleEngine
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What updating one channel's schedule did. */
enum class ScheduleOutcome {
    /** No stored schedule: generated from a staggered start, with now inside the first program. */
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
 *   regenerated. An airing whose item was deleted or whose file length changed, or an off-air stretch, is not kept. A
 *   schedule stored further ahead than the horizon (made when it was longer) is cut back the
 *   same way.
 */
@Singleton
class ScheduleUpdater @Inject constructor(
    private val channelDao: ChannelDao,
    private val scheduleDao: ScheduleDao,
    private val itemDao: MediaItemDao,
    private val schedules: ScheduleRepository,
    private val words: ChannelWordsSource,
    private val time: TimeSource,
    private val durations: MediaDurations,
) {
    private val mutex = Mutex()

    /** Runs [block] while no schedule is written; none is until it ends. */
    suspend fun <T> paused(block: suspend () -> T): T = mutex.withLock { block() }

    /** The whole library as channel rules see it, read in one query. */
    suspend fun catalog(): Catalog {
        durations.applySaved()
        return Catalog(itemDao.catalog(), words.words().types)
    }

    /** Updates every channel of every profile; [catalog] is the library as the caller just read it. */
    suspend fun updateAll(zone: ZoneId = ZoneId.systemDefault(), catalog: Catalog? = null): Map<ScheduleOutcome, Int> = mutex.withLock {
        val started = time.nowUtcMs()
        val channels = channelDao.all()
        if (channels.isEmpty()) return@withLock emptyMap()
        val library = catalog ?: catalog()
        val outcomes = channels.map { channel -> updateLocked(channel, library, zone) }
        val summary = outcomes.groupingBy { it }.eachCount()
        AppLog.i(TAG, "${channels.size} channels in ${time.nowUtcMs() - started} ms: $summary")
        summary
    }

    /**
     * Updates only the channels whose schedule needs it with nothing changed: none yet, marked
     * stale, or short of the horizon. Every change to a channel or the library updates its own
     * schedules, so this is all an app start or a quiet day needs; the library isn't even read
     * when no schedule is due.
     */
    suspend fun updateDue(zone: ZoneId = ZoneId.systemDefault()): Map<ScheduleOutcome, Int> = mutex.withLock {
        val started = time.nowUtcMs()
        val due = scheduleDao.dueChannels(started + HORIZON_MS - EXTEND_MARGIN_MS)
        if (due.isEmpty()) return@withLock emptyMap()
        val library = catalog()
        val outcomes = due.mapNotNull { id -> channelDao.byId(id)?.let { updateLocked(it, library, zone) } }
        val summary = outcomes.groupingBy { it }.eachCount()
        AppLog.i(TAG, "${due.size} due channels in ${time.nowUtcMs() - started} ms: $summary")
        summary
    }

    /** Updates one channel, e.g. right after its rules changed. */
    suspend fun update(channelId: Long, zone: ZoneId = ZoneId.systemDefault()): ScheduleOutcome? = mutex.withLock {
        val channel = channelDao.byId(channelId) ?: return@withLock null
        updateLocked(channel, catalog(), zone)
    }

    private suspend fun updateLocked(channel: ChannelEntity, catalog: Catalog, zone: ZoneId): ScheduleOutcome {
        if (channel.isHidden) return ScheduleOutcome.HIDDEN
        val setup = ChannelSetup(channel, channelDao.rules(channel.id), channelDao.seasons(channel.id), channelDao.dayparts(channel.id))
        val inputs = ChannelInputs.build(setup, catalog, zone)
        val hash = inputs.fingerprint()
        val now = time.nowUtcMs()
        val horizon = now + HORIZON_MS
        val stored = schedules.stored(channel.id)
        val staleLength = stored?.inputsHash == hash && hasStaleLength(channel.id, now, catalog)

        when {
            stored == null -> {
                val state = ScheduleEngine.staggered(inputs, ScheduleEngine.initialState(inputs, now), now)
                schedules.save(channel.id, state.nextStartUtcMs, ScheduleEngine.generate(inputs, state, horizon), hash)
                return ScheduleOutcome.CREATED
            }
            stored.inputsHash != hash || staleLength || stored.generatedUntilUtcMs > horizon + TRIM_MARGIN_MS -> {
                val kept = keepUntil(channel.id, now, catalog)
                val cursor = kept ?: now
                val removed = scheduleDao.programItemIdsFrom(channel.id, cursor).toLongArray()
                // The day before the cursor: what the repeat rules go on from.
                val aired = scheduleDao.programsBetween(channel.id, cursor - maxOf(inputs.config.movieGapMs, DAY_MS), cursor)
                    .map { PastAiring(it.mediaItemId, it.startUtcMs) }
                var state = ScheduleEngine.rewind(inputs, stored.state, cursor, removed, aired)
                // Nothing airs now: the schedule ran out, so it restarts like a new one.
                if (kept == null) state = ScheduleEngine.staggered(inputs, state, now, notBeforeUtcMs = minOf(stored.generatedUntilUtcMs, now))
                schedules.save(channel.id, state.nextStartUtcMs, ScheduleEngine.generate(inputs, state, horizon), hash)
                return ScheduleOutcome.REGENERATED
            }
            stored.generatedUntilUtcMs < horizon - EXTEND_MARGIN_MS -> {
                var state = stored.state
                // A schedule that ran out while nobody watched restarts now instead of filling the past.
                if (state.nextStartUtcMs < now) state = ScheduleEngine.staggered(inputs, state, now, notBeforeUtcMs = state.nextStartUtcMs)
                schedules.save(channel.id, state.nextStartUtcMs, ScheduleEngine.generate(inputs, state, horizon), hash)
                return ScheduleOutcome.EXTENDED
            }
            else -> return ScheduleOutcome.UP_TO_DATE
        }
    }

    /**
     * Where regeneration starts: after the current and next airings, unless they can't stay
     * (including a corrected file length).
     * Null when nothing airs now.
     */
    private suspend fun keepUntil(channelId: Long, now: Long, catalog: Catalog): Long? {
        val current = scheduleDao.slotAt(channelId, now)?.takeIf { now < it.endUtcMs } ?: return null
        if (!keeps(current, catalog)) return current.startUtcMs
        val next = scheduleDao.slotsFrom(channelId, current.endUtcMs, 1).firstOrNull() ?: return current.endUtcMs
        if (!keeps(next, catalog)) return next.startUtcMs
        return next.endUtcMs
    }

    private fun keeps(slot: ScheduleSlotEntity, catalog: Catalog): Boolean {
        if (slot.kind != SlotKind.PROGRAM) return false
        val item = slot.mediaItemId?.let { catalog[it] } ?: return false
        return !ProgramLength.differs(item.row.durationMs, slot.endUtcMs - slot.startUtcMs)
    }

    /** Also repairs protected slots left by an older app, even when the catalog hash already matches. */
    private suspend fun hasStaleLength(channelId: Long, now: Long, catalog: Catalog): Boolean {
        val current = scheduleDao.slotAt(channelId, now)?.takeIf { now < it.endUtcMs } ?: return false
        val next = scheduleDao.slotsFrom(channelId, current.endUtcMs, 1).firstOrNull()
        return listOfNotNull(current, next).any { slot ->
            slot.kind == SlotKind.PROGRAM && slot.mediaItemId?.let { catalog[it] } != null && !keeps(slot, catalog)
        }
    }

    companion object {
        private const val TAG = "Schedules"
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** How far ahead schedules are generated: the guide's 7 days, and a day to spare. */
        const val HORIZON_MS = 8 * DAY_MS

        /** A schedule is only extended once it is this much short of the horizon. */
        const val EXTEND_MARGIN_MS = DAY_MS / 2

        /** A schedule stored this much past the horizon is cut back to it. */
        private const val TRIM_MARGIN_MS = DAY_MS
    }
}
