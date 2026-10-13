package tv.anstelle.core.data.channels

import tv.anstelle.core.model.MediaType

/**
 * What a lineup airs: how many episodes, movies and music videos from how many series, how many
 * hours, and the share of the library's airtime each kind takes.
 */
data class LineupReach(
    val episodes: Int,
    val movies: Int,
    val videos: Int,
    val series: Int,
    val hours: Long,
    val episodeShare: Float,
    val movieShare: Float,
    val videoShare: Float,
) {
    val isEmpty: Boolean get() = episodes + movies + videos == 0
    val share: Float get() = episodeShare + movieShare + videoShare

    companion object {
        /** [result] against a library of [libraryMs] of airtime. */
        fun of(result: MatchResult, libraryMs: Long): LineupReach {
            var episodes = 0
            var movies = 0
            var videos = 0
            var episodeMs = 0L
            var movieMs = 0L
            var videoMs = 0L
            val series = HashSet<String>()
            for (match in result.matches) {
                val row = match.item.row
                row.seriesRemoteId?.let(series::add)
                when (row.type) {
                    MediaType.MOVIE -> { movies++; movieMs += row.durationMs }
                    MediaType.MUSIC_VIDEO -> { videos++; videoMs += row.durationMs }
                    else -> { episodes++; episodeMs += row.durationMs }
                }
            }
            fun share(ms: Long) = if (libraryMs <= 0) 0f else (ms.toDouble() / libraryMs).toFloat().coerceIn(0f, 1f)
            return LineupReach(
                episodes = episodes,
                movies = movies,
                videos = videos,
                series = series.size,
                hours = result.totalDurationMs / 3_600_000L,
                episodeShare = share(episodeMs),
                movieShare = share(movieMs),
                videoShare = share(videoMs),
            )
        }
    }
}
