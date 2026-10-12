package tv.anstelle.core.data.channels

/**
 * Once per day: each movie on a channel airs at most once in [GAP_MS]. A lineup keeps to it when
 * series fill the time between movies, or when its movies alone run a full day; otherwise the
 * movies would run out, so the rule doesn't apply. Items outside a series count as movies.
 */
object OncePerDay {
    const val GAP_MS = 24 * 3_600_000L

    /** Whether a lineup of [episodes] episodes and [movieMs] of movies keeps to it. */
    fun fits(episodes: Int, movieMs: Long): Boolean = episodes > 0 || movieMs >= GAP_MS

    fun fits(result: MatchResult): Boolean {
        var episodes = 0
        var movieMs = 0L
        for (match in result.matches) {
            val row = match.item.row
            if (row.seriesRemoteId != null) episodes++ else movieMs += row.durationMs
        }
        return fits(episodes, movieMs)
    }
}
