package tv.anstelle.core.data.playback

import tv.anstelle.core.model.WatchSync

/** What to do about the item's played state once playback stops. */
enum class AfterStop {
    NOTHING,

    /** The view counts as watching the item: mark it watched unless Jellyfin already did. */
    MARK_WATCHED,

    /**
     * Both mode reported an incomplete Live TV view, but Jellyfin moves the played mark on
     * its own: put back the mark the item had before.
     */
    RESTORE_PLAYED,
}

/**
 * When a view counts as watching an item, and what that means for Jellyfin's played mark.
 *
 * Completion follows the ending position, not how much of the beginning was seen. Live TV
 * additionally requires 30 seconds of actual playing time, including for short programs.
 */
object WatchRules {
    fun reachedEnd(stoppedAtMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && stoppedAtMs >= durationMs - durationMs / 10

    fun completed(positionMs: Long, durationMs: Long, ended: Boolean, onDemand: Boolean, playedMs: Long): Boolean =
        (onDemand || channelWatching(playedMs)) && (ended || reachedEnd(positionMs, durationMs))

    fun afterStop(sync: WatchSync, watched: Boolean, onDemand: Boolean): AfterStop = when {
        sync == WatchSync.OFF -> AfterStop.NOTHING
        // Jellyfin marks a stop past its own limit (90% unless the server changed it); a
        // watched view it left unmarked, from a lost stop or a stricter limit, is marked here.
        sync.marksWatched && watched -> AfterStop.MARK_WATCHED
        // Like Jellyfin's own apps: otherwise its marks stand.
        onDemand && sync == WatchSync.BOTH -> AfterStop.NOTHING
        sync == WatchSync.BOTH -> AfterStop.RESTORE_PLAYED
        else -> AfterStop.NOTHING
    }

    /** Whether the played mark before playback is needed when it stops. */
    fun needsPlayedBefore(sync: WatchSync, onDemand: Boolean): Boolean =
        sync == WatchSync.BOTH && !onDemand

    /**
     * Whether a channel has played long enough on screen ([playedMs], excluding pauses and buffering) to
     * count as watching its program, so Jellyfin hears of it: surfing past one doesn't.
     */
    fun channelWatching(playedMs: Long): Boolean = playedMs >= CHANNEL_WATCHING_MS

    /** How long a channel's program plays before it counts as watched at all. */
    const val CHANNEL_WATCHING_MS = 30_000L
}
