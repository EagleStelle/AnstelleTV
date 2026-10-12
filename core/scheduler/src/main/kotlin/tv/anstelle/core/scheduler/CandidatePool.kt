package tv.anstelle.core.scheduler

/**
 * One item a channel may air.
 *
 * [showKey] groups the episodes of one series (the Jellyfin series id); items outside a series,
 * such as movies, leave it null and count as a show of their own. [weight] scales how often the
 * show is picked; items with a weight of 0 or less never air. [releaseUtcMs] orders items outside
 * a series by release date.
 */
class Candidate(
    val id: Long,
    val durationMs: Long,
    val showKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val weight: Float = 1f,
    val ratingScore: Int? = null,
    val releaseUtcMs: Long? = null,
)

/**
 * A channel's candidates in canonical order, held in primitive arrays: episodes by series, season,
 * episode and id, then the movies by release date and id. The order never depends on how the
 * caller loaded the rows, so the same library always gives the same schedule. Items without a
 * positive duration or weight are dropped.
 *
 * Specials (season 0) sort after the regular seasons, and items without a season or episode
 * number sort last, so a series starts at S1E1. Movies without a release date sort last.
 */
class CandidatePool private constructor(
    internal val ids: LongArray,
    internal val durations: LongArray,
    internal val seasonKeys: IntArray,
    internal val episodeKeys: IntArray,
    internal val releaseKeys: LongArray,
    /** Rating score per item, or [NO_RATING]. */
    internal val ratings: IntArray,
    /** Show index per item. */
    internal val showOf: IntArray,
    /** Each show's items are the range `[showStart, showEnd)`. */
    internal val showStart: IntArray,
    internal val showEnd: IntArray,
    internal val showKeys: Array<String?>,
    internal val showWeights: DoubleArray,
    /** Items from here on are movies: outside any series. */
    internal val moviesStart: Int,
) {
    val size: Int get() = ids.size
    val showCount: Int get() = showKeys.size

    /** Hash of everything the engine reads from the pool. */
    val fingerprint: Long = Fingerprint().apply {
        add(ids.size)
        for (i in ids.indices) {
            add(ids[i]).add(durations[i]).add(seasonKeys[i]).add(episodeKeys[i]).add(releaseKeys[i]).add(ratings[i])
        }
        for (s in showKeys.indices) add(showKeys[s]).add(showWeights[s].toRawBits())
    }.value

    private val indexById: HashMap<Long, Int> by lazy {
        HashMap<Long, Int>(ids.size * 2).also { map -> ids.forEachIndexed { i, id -> map[id] = i } }
    }
    private val showByKey: HashMap<String, Int> by lazy {
        HashMap<String, Int>(showKeys.size * 2).also { map ->
            showKeys.forEachIndexed { s, key -> if (key != null) map[key] = s }
        }
    }

    fun id(index: Int): Long = ids[index]

    internal fun isMovie(index: Int): Boolean = index >= moviesStart

    internal fun indexOfId(id: Long): Int = indexById[id] ?: -1

    internal fun showIndexOf(key: String): Int = showByKey[key] ?: -1

    /** Compares episode [index] with an episode position, in canonical order. */
    internal fun compareKey(index: Int, key: EpisodeKey): Int {
        val bySeason = seasonKeys[index].compareTo(key.season)
        if (bySeason != 0) return bySeason
        val byEpisode = episodeKeys[index].compareTo(key.episode)
        if (byEpisode != 0) return byEpisode
        return ids[index].compareTo(key.id)
    }

    /** Compares movie [index] with a release position, in canonical order. */
    internal fun compareRelease(index: Int, key: ReleaseKey): Int {
        val byRelease = releaseKeys[index].compareTo(key.release)
        if (byRelease != 0) return byRelease
        return ids[index].compareTo(key.id)
    }

    companion object {
        internal const val NO_RATING = Int.MIN_VALUE

        val EMPTY: CandidatePool by lazy { of(emptyList()) }

        fun of(candidates: Collection<Candidate>): CandidatePool {
            val kept = candidates.filter { it.durationMs > 0 && it.weight > 0f }
            require(kept.distinctBy { it.id }.size == kept.size) { "Candidate ids must be unique" }
            val sorted = kept.sortedWith(CanonicalOrder)
            val n = sorted.size

            val showOf = IntArray(n)
            val starts = ArrayList<Int>()
            val keys = ArrayList<String?>()
            val weights = ArrayList<Double>()
            for (i in 0 until n) {
                val key = sorted[i].showKey
                val sameShow = i > 0 && key != null && key == sorted[i - 1].showKey
                if (sameShow) {
                    weights[weights.lastIndex] = maxOf(weights.last(), sorted[i].weight.toDouble())
                } else {
                    starts += i
                    keys += key
                    weights += sorted[i].weight.toDouble()
                }
                showOf[i] = starts.lastIndex
            }
            val showEnd = IntArray(starts.size) { s -> if (s + 1 < starts.size) starts[s + 1] else n }

            return CandidatePool(
                ids = LongArray(n) { sorted[it].id },
                durations = LongArray(n) { sorted[it].durationMs },
                seasonKeys = IntArray(n) { seasonKey(sorted[it].season) },
                episodeKeys = IntArray(n) { episodeKey(sorted[it].episode) },
                releaseKeys = LongArray(n) { releaseKey(sorted[it].releaseUtcMs) },
                ratings = IntArray(n) { sorted[it].ratingScore ?: NO_RATING },
                showOf = showOf,
                showStart = starts.toIntArray(),
                showEnd = showEnd,
                showKeys = keys.toTypedArray(),
                showWeights = weights.toDoubleArray(),
                moviesStart = sorted.indexOfFirst { it.showKey == null }.takeIf { it >= 0 } ?: n,
            )
        }

        private fun seasonKey(season: Int?): Int = when (season) {
            null -> Int.MAX_VALUE
            0 -> Int.MAX_VALUE - 1
            else -> season
        }

        private fun episodeKey(episode: Int?): Int = episode ?: Int.MAX_VALUE

        private fun releaseKey(releaseUtcMs: Long?): Long = releaseUtcMs ?: Long.MAX_VALUE

        // Series first (movies last); episodes by season, episode and id, movies by release and id.
        private val CanonicalOrder = compareBy<Candidate, String?>(nullsLast()) { it.showKey }
            .thenComparator { a, b ->
                if (a.showKey == null) {
                    compareValuesBy(a, b, { releaseKey(it.releaseUtcMs) }, { it.id })
                } else {
                    compareValuesBy(a, b, { seasonKey(it.season) }, { episodeKey(it.episode) }, { it.id })
                }
            }
    }
}
