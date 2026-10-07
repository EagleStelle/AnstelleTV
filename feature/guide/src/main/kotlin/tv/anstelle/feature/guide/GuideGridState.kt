package tv.anstelle.feature.guide

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tv.anstelle.core.data.guide.ProgramStrip

/**
 * Where the guide grid is: the visible hours, shared by every row, and the selected cell.
 *
 * The selection is a channel and a time inside the selected cell. Moving up or down keeps
 * the time, so focus stays on what airs at that moment; moving left or right steps to the
 * neighboring cell and pages the hours when it leaves the view. All of it is snapshot state
 * read in layout and drawing, so scrolling the hours relays rows out without recomposing
 * them.
 */
@Stable
class GuideGridState(openingUtcMs: Long, val pxPerMs: Float) {
    /** The left edge of the visible hours. */
    var windowStart by mutableLongStateOf(openingUtcMs)

    /** Where the window is headed; key navigation animates [windowStart] there. */
    var targetStart by mutableLongStateOf(openingUtcMs)

    /** Width of the program area in pixels. */
    var widthPx by mutableIntStateOf(0)

    var selectedChannel by mutableLongStateOf(NONE)
    var selectedTime by mutableLongStateOf(openingUtcMs)

    /** True while the grid has D-pad focus. */
    var focused by mutableStateOf(false)

    val spanMs: Long get() = (widthPx / pxPerMs).toLong()

    /**
     * Selects what [channelId] airs at the first visible moment that hasn't gone by: the
     * program on now while the hours start at the current half hour.
     */
    fun reset(channelId: Long, nowMs: Long) {
        selectedChannel = channelId
        selectedTime = maxOf(targetStart, nowMs)
        reveal(selectedTime, nowMs)
    }

    /** Selects what [channelId] airs at [nowMs], paging the hours back to it. */
    fun selectNow(channelId: Long, nowMs: Long) {
        selectedChannel = channelId
        selectedTime = nowMs
        reveal(nowMs, nowMs)
    }

    /** Moves hours that have gone by (the guide was away a while) up to the current half hour. */
    fun catchUp(nowMs: Long) {
        val earliest = earliest(nowMs)
        if (targetStart >= earliest) return
        windowStart = earliest
        targetStart = earliest
        if (selectedTime < earliest) selectedTime = earliest
    }

    fun moveRight(strip: ProgramStrip?, nowMs: Long) {
        val i = strip?.indexAt(selectedTime) ?: -1
        val next = if (i >= 0) strip!!.ends[i] else maxOf(selectedTime, targetStart) + HALF_HOUR_MS
        if (next > nowMs + MAX_AHEAD_MS) return
        selectedTime = next
        reveal(next, nowMs)
    }

    /** False when the selected cell already reaches the guide's left edge, so focus may leave the grid. */
    fun moveLeft(strip: ProgramStrip?, nowMs: Long): Boolean {
        val earliest = earliest(nowMs)
        val i = strip?.indexAt(selectedTime) ?: -1
        val start = if (i >= 0) strip!!.starts[i] else selectedTime
        if (start <= earliest) return false
        val previous = if (i > 0 && strip!!.ends[i - 1] == start) strip.starts[i - 1] else start - HALF_HOUR_MS
        selectedTime = maxOf(previous, earliest)
        reveal(selectedTime, nowMs)
        return true
    }

    /** Selects the channel [delta] rows away; false when that leaves the list. */
    fun moveVertically(channels: List<GuideChannel>, delta: Int): Boolean {
        val index = channels.indexOfFirst { it.id == selectedChannel }
        val target = index + delta
        if (index < 0 || target !in channels.indices) return false
        selectedChannel = channels[target].id
        if (selectedTime < targetStart) selectedTime = targetStart
        return true
    }

    /** Selects the cell under a tap at [x] pixels into [channelId]'s program area. */
    fun tap(channelId: Long, x: Float) {
        selectedChannel = channelId
        selectedTime = windowStart + (x / pxPerMs).toLong()
    }

    /** Moves the hours by [dx] pixels (a touch drag or fling); false once a limit is reached. */
    fun scrollByPx(dx: Float, nowMs: Long): Boolean {
        val wanted = windowStart + (dx / pxPerMs).toLong()
        val clamped = wanted.coerceIn(earliest(nowMs), nowMs + MAX_AHEAD_MS)
        windowStart = clamped
        targetStart = clamped
        return clamped == wanted
    }

    /** Pages the hours so [timeMs] is in view and readable. */
    private fun reveal(timeMs: Long, nowMs: Long) {
        val span = spanMs.coerceAtLeast(HALF_HOUR_MS)
        val earliest = earliest(nowMs)
        targetStart = when {
            timeMs < targetStart -> maxOf(earliest, halfHourOf(timeMs))
            timeMs >= targetStart + span - REVEAL_MARGIN_MS -> maxOf(earliest, halfHourOf(timeMs) - HALF_HOUR_MS)
            else -> return
        }
    }

    companion object {
        const val NONE = -1L
        const val HALF_HOUR_MS = 30 * 60_000L

        /** How far ahead the guide goes. */
        const val MAX_AHEAD_MS = 7 * 24 * 60 * 60_000L

        /** A cell starting this close to the right edge pages the hours. */
        private const val REVEAL_MARGIN_MS = 15 * 60_000L

        fun halfHourOf(timeMs: Long): Long = Math.floorDiv(timeMs, HALF_HOUR_MS) * HALF_HOUR_MS

        /** The guide starts at the current half hour; earlier airings are gone. */
        fun earliest(nowMs: Long): Long = halfHourOf(nowMs)
    }
}
