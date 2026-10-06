package tv.anstelle.core.data.playback

import tv.anstelle.core.model.WatchReportMode

/** What to do about the item's played state once a report says playback stopped. */
enum class AfterStop {
    NOTHING,

    /** Jellyfin may have marked the item played after a partial view: check, and unmark it. */
    UNMARK_IF_PLAYED,

    /** A full view counts: if the server marked the item played, record it locally too. */
    RECORD_PLAYED,
}

/**
 * Channels start programs mid-way, so Jellyfin often sees a viewer "finish" an episode they
 * joined at minute 15 and marks it played. In [WatchReportMode.REPORT_NO_MARK] mode such
 * partial views are reported but don't leave the item played; only a view from (near) the
 * start to (near) the end does.
 */
object WatchRules {
    /** A view counts as full when it joined in the first two minutes (or 5%) and lasted to 90%. */
    fun isFullView(joinedAtMs: Long, stoppedAtMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val joinedEarly = joinedAtMs <= maxOf(EARLY_JOIN_MS, durationMs / 20)
        val reachedEnd = stoppedAtMs * 10 >= durationMs * 9
        return joinedEarly && reachedEnd
    }

    fun afterStop(mode: WatchReportMode, playedBefore: Boolean, fullView: Boolean): AfterStop = when (mode) {
        WatchReportMode.OFF -> AfterStop.NOTHING
        WatchReportMode.FULL -> AfterStop.RECORD_PLAYED
        WatchReportMode.REPORT_NO_MARK -> when {
            fullView -> AfterStop.RECORD_PLAYED
            playedBefore -> AfterStop.NOTHING
            else -> AfterStop.UNMARK_IF_PLAYED
        }
    }

    private const val EARLY_JOIN_MS = 2 * 60_000L
}
