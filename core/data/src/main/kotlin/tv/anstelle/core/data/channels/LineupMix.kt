package tv.anstelle.core.data.channels

/** What a lineup airs, as far as its playback goes. */
data class LineupMix(val series: Boolean, val movies: Boolean, val fitsOncePerDay: Boolean) {
    companion object {
        fun of(result: MatchResult) = LineupMix(
            series = result.matches.any { it.item.row.seriesRemoteId != null },
            movies = result.matches.any { it.item.row.seriesRemoteId == null },
            fitsOncePerDay = OncePerDay.fits(result),
        )
    }
}
