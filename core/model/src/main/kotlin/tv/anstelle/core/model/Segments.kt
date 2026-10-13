package tv.anstelle.core.model

/** The kinds of media segment Jellyfin marks; [key] names the profile's setting for each. */
enum class SegmentType(val key: String) {
    INTRO("intro"),
    OUTRO("outro"),
    RECAP("recap"),
    PREVIEW("preview"),
    COMMERCIAL("commercial"),
}

/**
 * A stretch of an item Jellyfin marks by kind (its media segments, 10.10 and later), from a
 * segment provider such as the Intro Skipper plugin. Only the on-demand player uses them:
 * channels play like broadcast TV.
 */
data class MediaSegment(val type: SegmentType, val startMs: Long, val endMs: Long)

/**
 * The segment playing at [positionMs], or null. A segment's last [SEGMENT_TAIL_MS] don't
 * count, so nothing is offered for a skip that would gain nothing; where two overlap, the one
 * that started first wins.
 */
fun List<MediaSegment>.playingAt(positionMs: Long): MediaSegment? =
    firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - SEGMENT_TAIL_MS }

/** How close to its end a segment no longer counts as playing. */
const val SEGMENT_TAIL_MS = 1_000L
