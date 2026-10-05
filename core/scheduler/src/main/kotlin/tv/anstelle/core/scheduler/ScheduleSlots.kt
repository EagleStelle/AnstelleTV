package tv.anstelle.core.scheduler

import tv.anstelle.core.model.NowPlaying
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.model.codeOf

/**
 * Generated slots in start order, as primitive arrays (one entry per `schedule_slot` row).
 * Each slot is one airing: a whole program, or an off-air stretch.
 */
class ScheduleSlots internal constructor(
    val size: Int,
    private val kinds: ByteArray,
    private val mediaItemIds: LongArray,
    private val starts: LongArray,
    private val ends: LongArray,
) {
    fun kind(i: Int): SlotKind = codeOf(kinds[i].toInt())

    /** The program's media item, or null when off air. */
    fun mediaItemId(i: Int): Long? = if (kinds[i].toInt() == SlotKind.PROGRAM.code) mediaItemIds[i] else null

    fun startUtcMs(i: Int): Long = starts[i]

    fun endUtcMs(i: Int): Long = ends[i]

    /** Index of the slot airing at [nowUtcMs], or -1. Same rule as the SQL tuning query. */
    fun indexAt(nowUtcMs: Long): Int {
        var low = 0
        var high = size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= nowUtcMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (found >= 0 && nowUtcMs < ends[found]) found else -1
    }

    fun nowPlaying(channelId: Long, nowUtcMs: Long): NowPlaying? {
        val i = indexAt(nowUtcMs)
        if (i < 0) return null
        return nowPlaying(channelId, kind(i), mediaItemId(i), starts[i], ends[i], nowUtcMs)
    }
}

/**
 * What a slot airs at [nowUtcMs]: the media position is the time since the slot started.
 * Returns null when [nowUtcMs] is outside `[startUtcMs, endUtcMs)`.
 */
fun nowPlaying(
    channelId: Long,
    kind: SlotKind,
    mediaItemId: Long?,
    startUtcMs: Long,
    endUtcMs: Long,
    nowUtcMs: Long,
): NowPlaying? {
    if (nowUtcMs < startUtcMs || nowUtcMs >= endUtcMs) return null
    return NowPlaying(
        channelId = channelId,
        kind = kind,
        mediaItemId = mediaItemId,
        slotStartUtcMs = startUtcMs,
        slotEndUtcMs = endUtcMs,
        mediaPositionMs = nowUtcMs - startUtcMs,
    )
}

/** Growable column buffer the engine appends slots to. */
internal class SlotBuffer {
    private var size = 0
    private var kinds = ByteArray(INITIAL)
    private var mediaItemIds = LongArray(INITIAL)
    private var starts = LongArray(INITIAL)
    private var ends = LongArray(INITIAL)

    fun program(mediaItemId: Long, start: Long, end: Long) = add(SlotKind.PROGRAM, mediaItemId, start, end)

    fun offAir(start: Long, end: Long) = add(SlotKind.OFF_AIR, 0L, start, end)

    private fun add(kind: SlotKind, mediaItemId: Long, start: Long, end: Long) {
        check(end > start) { "Empty slot at $start" }
        if (size == starts.size) grow()
        kinds[size] = kind.code.toByte()
        mediaItemIds[size] = mediaItemId
        starts[size] = start
        ends[size] = end
        size++
    }

    private fun grow() {
        val capacity = starts.size * 2
        kinds = kinds.copyOf(capacity)
        mediaItemIds = mediaItemIds.copyOf(capacity)
        starts = starts.copyOf(capacity)
        ends = ends.copyOf(capacity)
    }

    fun build() = ScheduleSlots(size, kinds.copyOf(size), mediaItemIds.copyOf(size), starts.copyOf(size), ends.copyOf(size))

    private companion object {
        const val INITIAL = 256
    }
}
