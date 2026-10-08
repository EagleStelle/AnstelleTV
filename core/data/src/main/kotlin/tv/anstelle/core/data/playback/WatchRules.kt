package tv.anstelle.core.data.playback

import tv.anstelle.core.model.WatchSync
import java.util.BitSet

/** What to do about the item's played state once playback stops. */
enum class AfterStop {
    NOTHING,

    /** The view counts as watching the item: mark it watched unless Jellyfin already did. */
    MARK_WATCHED,

    /**
     * Playback was reported for its progress only, but Jellyfin moves the played mark on
     * its own (off at the start, on past 90%): put back the mark the item had before.
     */
    RESTORE_PLAYED,
}

/**
 * When a view counts as watching an item, and what that means for Jellyfin's played mark.
 *
 * A channel joins programs mid-way and lets the viewer seek, so only what actually played on
 * screen counts there: a program is watched when [WatchCoverage] covers 90% of it. On demand
 * the viewer picked the item and the spot, so stopping past 90% counts, as in Jellyfin's apps.
 */
object WatchRules {
    fun watchedThrough(coveredMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && coveredMs * 10 >= durationMs * 9

    fun reachedEnd(stoppedAtMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && stoppedAtMs * 10 >= durationMs * 9

    fun afterStop(sync: WatchSync, watched: Boolean, onDemand: Boolean): AfterStop = when {
        sync == WatchSync.OFF -> AfterStop.NOTHING
        // Like Jellyfin's own apps: its marks stand.
        onDemand && sync == WatchSync.BOTH -> AfterStop.NOTHING
        sync.marksWatched && watched -> AfterStop.MARK_WATCHED
        sync.savesProgress -> AfterStop.RESTORE_PLAYED
        else -> AfterStop.NOTHING
    }

    /** Whether the played mark before playback is needed when it stops. */
    fun needsPlayedBefore(sync: WatchSync, onDemand: Boolean): Boolean =
        sync.savesProgress && !(onDemand && sync == WatchSync.BOTH)
}

/**
 * The seconds of an item that actually played, so joining late or seeking past a part never
 * counts as watching it. The player samples its position about once a second while playing;
 * two samples close together mean the stretch between them played.
 */
class WatchCoverage(private val durationMs: Long) {
    private val seconds = BitSet()
    private var lastMs = -1L

    fun sample(positionMs: Long) {
        val position = positionMs.coerceIn(0, durationMs)
        val from = if (lastMs >= 0 && position - lastMs in 0..MAX_STEP_MS) lastMs else position
        seconds.set((from / 1000).toInt(), (position / 1000).toInt() + 1)
        lastMs = position
    }

    val coveredMs: Long get() = minOf(seconds.cardinality() * 1000L, durationMs)

    private companion object {
        /** Longer than a tick and its jitter, shorter than the players' 10 s skips. */
        const val MAX_STEP_MS = 3_000L
    }
}
