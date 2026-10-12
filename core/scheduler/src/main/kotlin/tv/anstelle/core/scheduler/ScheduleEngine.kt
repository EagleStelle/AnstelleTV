package tv.anstelle.core.scheduler

import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.SlotKind

class ScheduleResult(val slots: ScheduleSlots, val state: EngineState)

/** A program kept before the point a schedule is regenerated from. */
class PastAiring(val itemId: Long, val startUtcMs: Long)

/**
 * Builds channel schedules. Pure: no clock, no I/O, no shared state. The same inputs and
 * state always give the same slots.
 *
 * - **Back to back:** each program airs for exactly its own length and the next one starts
 *   the moment it ends.
 * - **Lineup:** each program uses the seasonal lineup whose date range contains its local
 *   start date, else the base pool. A daypart that contains its local start time narrows
 *   that lineup further. When nothing is eligible, the channel is off air until the next
 *   daypart edge or local midnight, whichever comes first.
 * - **Series and movies:** each program is a series' episode or a movie, as a show picked by
 *   weight decides (a movie is a show of its own). Each kind then follows its own playback:
 *   SHUFFLE deals every eligible episode, or movie, once per deck; EPISODE_ORDER plays the picked
 *   series' next episode, or the next movie by release date; BLOCKS plays 2–4 of those in a row.
 *   In order, a series opens at an episode and the movies at a movie picked from the seed.
 * - **Repeats:** an item never airs right after itself, and a movie never starts within
 *   `movieGapMs` of its last start, while anything else on the lineup can air instead. When
 *   nothing else can, the gap gives way first, then the repeat.
 */
object ScheduleEngine {
    /** A fresh state whose first program starts at [startUtcMs]. */
    fun initialState(inputs: ScheduleInputs, startUtcMs: Long): EngineState =
        EngineState(nextStartUtcMs = startUtcMs, rngState = inputs.config.seed)

    /**
     * [state] moved back so that [nowUtcMs] falls at a random point of its first program, as if
     * the channel had been on air for a while: channels started together don't all air from the
     * top. The point comes from the state, so the same state always gives the same start, and it
     * is never before [notBeforeUtcMs]. Off air starts at [nowUtcMs]. When a daypart or season
     * edge falls inside the lead, a different first program may air; the start stays staggered.
     */
    fun staggered(inputs: ScheduleInputs, state: EngineState, nowUtcMs: Long, notBeforeUtcMs: Long = Long.MIN_VALUE): EngineState {
        val first = Run(inputs, state.copy(nextStartUtcMs = nowUtcMs)).generate(nowUtcMs + 1).slots
        val lead = if (first.size > 0 && first.kind(0) == SlotKind.PROGRAM) {
            val fraction = SplitMix64(Fingerprint().add(state.rngState).add(STAGGER).value).nextDouble()
            (fraction * (first.endUtcMs(0) - first.startUtcMs(0))).toLong()
        } else {
            0L
        }
        return state.copy(nextStartUtcMs = maxOf(nowUtcMs - lead, notBeforeUtcMs))
    }

    /**
     * Generates programs from `state.nextStartUtcMs` until one starts at or after [untilUtcMs].
     * The last airing may run past [untilUtcMs]; the returned state continues after it.
     */
    fun generate(inputs: ScheduleInputs, state: EngineState, untilUtcMs: Long): ScheduleResult =
        Run(inputs, state).generate(untilUtcMs)

    /**
     * Prepares a state for regenerating from [cursorUtcMs] after the inputs changed.
     * [removedItemIds] are the programs of the discarded slots, in airing order: each series
     * resumes at the first of its episodes that was discarded, and the movies' release order at
     * the first discarded movie, so nothing is skipped. [aired] are the programs kept before the
     * cursor, oldest first, reaching back at least the movie gap: the repeat rules go on from
     * them. A running block ends, and shuffle decks start over from what aired before the cursor.
     */
    fun rewind(
        inputs: ScheduleInputs,
        state: EngineState,
        cursorUtcMs: Long,
        removedItemIds: LongArray,
        aired: List<PastAiring>,
    ): EngineState {
        val cursors = HashMap(state.showCursors)
        var movieCursor = state.movieCursor
        var movieRewound = false
        val rewound = HashSet<String>()
        val pools = listOf(inputs.pool) + inputs.seasons.map { it.pool }
        for (id in removedItemIds) {
            for (pool in pools) {
                val i = pool.indexOfId(id)
                if (i < 0) continue
                val show = pool.showKeys[pool.showOf[i]]
                if (show == null) {
                    if (!movieRewound) movieCursor = ReleaseKey(pool.releaseKeys[i], pool.ids[i])
                    movieRewound = true
                } else if (rewound.add(show)) {
                    cursors[show] = EpisodeKey(pool.seasonKeys[i], pool.episodeKeys[i], pool.ids[i])
                }
                break
            }
        }
        val gap = inputs.config.movieGapMs
        val movieStarts = HashMap<Long, Long>()
        for (airing in aired) {
            val isMovie = pools.any { pool -> pool.indexOfId(airing.itemId).let { it >= 0 && pool.isMovie(it) } }
            if (isMovie && airing.startUtcMs + gap > cursorUtcMs) movieStarts[airing.itemId] = airing.startUtcMs
        }
        return state.copy(
            nextStartUtcMs = cursorUtcMs,
            showCursors = cursors,
            movieCursor = movieCursor,
            decks = emptyMap(),
            blockShow = null,
            blockRemaining = 0,
            lastItemId = aired.lastOrNull()?.itemId ?: EngineState.NO_ID,
            movieStarts = movieStarts,
        )
    }
}

/** A lineup under one daypart: what the eligible items are keyed by. */
private data class LineupKey(val pool: Long, val daypart: Int)

/** The eligible part of a lineup under one daypart. */
private class Eligible(pool: CandidatePool, daypart: Daypart?) {
    val itemMask = BooleanArray(pool.size) { daypart == null || daypart.allows(pool, it) }
    val episodes: IntArray = (0 until pool.moviesStart).filter { itemMask[it] }.toIntArray()
    val movies: IntArray = (pool.moviesStart until pool.size).filter { itemMask[it] }.toIntArray()
    val showMask = BooleanArray(pool.showCount)

    /** Every show with an eligible item, series and movies, with cumulative weights. */
    val shows: IntArray
    val showWeights: DoubleArray

    /** The series alone, with cumulative weights. */
    val series: IntArray
    val seriesWeights: DoubleArray
    val episodeFingerprint: Long
    val movieFingerprint: Long

    init {
        for (i in itemMask.indices) if (itemMask[i]) showMask[pool.showOf[i]] = true
        shows = showMask.indices.filter { showMask[it] }.toIntArray()
        showWeights = cumulative(pool, shows)
        series = shows.filter { pool.showKeys[it] != null }.toIntArray()
        seriesWeights = cumulative(pool, series)
        episodeFingerprint = fingerprint(pool, episodes)
        movieFingerprint = fingerprint(pool, movies)
    }

    private fun cumulative(pool: CandidatePool, shows: IntArray): DoubleArray {
        var total = 0.0
        return DoubleArray(shows.size) { k ->
            total += pool.showWeights[shows[k]]
            total
        }
    }

    private fun fingerprint(pool: CandidatePool, items: IntArray): Long = Fingerprint().apply {
        add(items.size)
        for (i in items) add(pool.ids[i])
    }.value
}

private class Run(private val inputs: ScheduleInputs, state: EngineState) {
    private val config = inputs.config
    private val gap = config.movieGapMs
    private val zone = ZoneClock(inputs.zone)
    private val rng = SplitMix64(state.rngState)
    private var cursor = state.nextStartUtcMs
    private val showCursors = HashMap(state.showCursors)
    private var movieCursor = state.movieCursor
    private val decks = HashMap(state.decks)
    private var blockShow = state.blockShow
    private var blockRemaining = state.blockRemaining
    private var lastItemId = state.lastItemId
    private val movieStarts = HashMap(state.movieStarts)
    private val out = SlotBuffer()

    private val eligibleCache = HashMap<LineupKey, Eligible>()
    private val deckOrders = HashMap<DeckKey, IntArray>()

    // The lineup the program being picked comes from.
    private var pool = inputs.pool
    private var poolKey = BASE_POOL_KEY
    private var daypart = -1
    private lateinit var eligible: Eligible

    fun generate(untilUtcMs: Long): ScheduleResult {
        while (cursor < untilUtcMs) step()
        // Starts the gap no longer covers change nothing.
        movieStarts.values.removeAll { it + gap <= cursor }
        val state = EngineState(
            nextStartUtcMs = cursor,
            rngState = rng.state,
            showCursors = showCursors,
            movieCursor = movieCursor,
            decks = decks,
            blockShow = blockShow,
            blockRemaining = blockRemaining,
            lastItemId = lastItemId,
            movieStarts = movieStarts,
        )
        return ScheduleResult(out.build(), state)
    }

    private fun step() {
        val local = zone.localMs(cursor)
        val seasonIndex = seasonAt(local)
        pool = if (seasonIndex < 0) inputs.pool else inputs.seasons[seasonIndex].pool
        poolKey = if (seasonIndex < 0) BASE_POOL_KEY else inputs.seasons[seasonIndex].key
        val minute = zone.minuteOfDay(local)
        daypart = inputs.dayparts.indexOfFirst { it.contains(minute) }
        eligible = eligibleCache.getOrPut(LineupKey(poolKey, daypart)) { Eligible(pool, inputs.dayparts.getOrNull(daypart)) }

        val index = pick()
        if (index < 0) offAir(local) else air(index)
    }

    private fun air(index: Int) {
        val id = pool.ids[index]
        if (gap > 0 && pool.isMovie(index)) movieStarts[id] = cursor
        lastItemId = id
        val end = cursor + pool.durations[index]
        out.program(id, cursor, end)
        cursor = end
    }

    private fun seasonAt(local: Long): Int {
        if (inputs.seasons.isEmpty()) return -1
        val month = zone.month(local)
        val day = zone.dayOfMonth(local)
        return inputs.seasons.indexOfFirst { it.range.contains(month, day) }
    }

    /**
     * The next program, or -1 when nothing is eligible. A running block goes on; otherwise a
     * show picked by weight says whether a series or a movie airs, and the other kind steps in
     * when the picked one has nothing the repeat rules allow. Only when neither kind has, the
     * rules give way one at a time.
     */
    private fun pick(): Int {
        val continued = continueBlock()
        if (continued >= 0) return continued
        val drawn = draw(eligible.shows, eligible.showWeights, exclude = -1)
        if (drawn < 0) return -1
        val moviesFirst = pool.showKeys[drawn] == null
        for (level in STRICT..ANY) {
            val first = if (moviesFirst) movie(level) else series(drawn, level)
            if (first >= 0) return first
            val second = if (moviesFirst) series(-1, level) else movie(level)
            if (second >= 0) return second
        }
        error("Eligible shows without an eligible item")
    }

    /** The next item of the running block, or -1 when none runs or it can't go on. */
    private fun continueBlock(): Int {
        if (blockRemaining <= 0) return -1
        val key = blockShow
        val index = if (key == null) {
            nextMovie(STRICT)
        } else {
            val show = pool.showIndexOf(key)
            if (show >= 0 && eligible.showMask[show]) {
                peekEpisode(show).takeIf { allowed(it, RELAXED) }?.also { takeEpisode(show, it) } ?: -1
            } else {
                -1
            }
        }
        if (index < 0) {
            blockShow = null
            blockRemaining = 0
        } else {
            blockRemaining--
        }
        return index
    }

    /** An episode by the series playback: from [drawn] (or a series picked now), or -1. */
    private fun series(drawn: Int, level: Int): Int {
        if (eligible.series.isEmpty()) return -1
        if (config.series.order == PlaybackOrder.SHUFFLE) return deal(movies = false, level)
        var show = if (drawn >= 0) drawn else draw(eligible.series, eligible.seriesWeights, exclude = -1)
        var index = peekEpisode(show)
        if (!allowed(index, level)) {
            // Only a series whose one eligible episode just aired gets here.
            show = draw(eligible.series, eligible.seriesWeights, exclude = show)
            if (show < 0) return -1
            index = peekEpisode(show)
            if (!allowed(index, level)) return -1
        }
        takeEpisode(show, index)
        if (config.series.order == PlaybackOrder.BLOCKS) {
            blockShow = pool.showKeys[show]
            blockRemaining = minOf(config.series.blockSize, pool.showEnd[show] - pool.showStart[show]) - 1
        }
        return index
    }

    /** A movie by the movie playback, or -1. */
    private fun movie(level: Int): Int {
        if (eligible.movies.isEmpty()) return -1
        return when (config.movies.order) {
            PlaybackOrder.SHUFFLE -> deal(movies = true, level)
            PlaybackOrder.EPISODE_ORDER -> nextMovie(level)
            PlaybackOrder.BLOCKS -> nextMovie(level).also { index ->
                if (index >= 0) {
                    blockShow = null
                    blockRemaining = minOf(config.movies.blockSize, eligible.movies.size) - 1
                }
            }
        }
    }

    /** Whether [index] may air now: [STRICT] keeps both repeat rules, [RELAXED] only "not right after itself". */
    private fun allowed(index: Int, level: Int): Boolean {
        if (level == ANY) return true
        val id = pool.ids[index]
        if (id == lastItemId) return false
        if (level == RELAXED || gap == 0L || !pool.isMovie(index)) return true
        val last = movieStarts[id] ?: return true
        return last + gap <= cursor
    }

    /** The next card of the episodes' or the movies' deck when [level] allows it, else -1. */
    private fun deal(movies: Boolean, level: Int): Int {
        val items = if (movies) eligible.movies else eligible.episodes
        val key = DeckKey(poolKey, daypart, movies)
        val fingerprint = if (movies) eligible.movieFingerprint else eligible.episodeFingerprint
        val stored = decks[key]
        val deck = if (stored == null || stored.fingerprint != fingerprint || stored.position >= items.size) {
            deckOrders.remove(key)
            DeckState(fingerprint, rng.nextLong(), lastItemId, 0, if (movies) holds(items) else emptyList())
                .also { decks[key] = it }
        } else {
            stored
        }
        val order = deckOrders.getOrPut(key) { deckOrder(deck, items) }
        val index = order[deck.position]
        if (!allowed(index, level)) return -1
        decks[key] = deck.copy(position = deck.position + 1)
        return index
    }

    /** The movies among [items] that the gap still holds back, with how long from now. */
    private fun holds(items: IntArray): List<DeckHold> {
        if (gap == 0L || movieStarts.isEmpty()) return emptyList()
        return items.asList().mapNotNull { i ->
            val id = pool.ids[i]
            movieStarts[id]?.let { start -> start + gap - cursor }?.takeIf { it > 0 }?.let { DeckHold(id, it) }
        }
    }

    /**
     * The deck's order, rebuilt from its seed. It never opens with the item that aired before
     * it, and held movies wait out their hold.
     */
    private fun deckOrder(deck: DeckState, items: IntArray): IntArray {
        val order = items.copyOf()
        val deckRng = SplitMix64(deck.seed)
        shuffle(order, deckRng)
        if (order.size > 1 && pool.ids[order[0]] == deck.prevId) swap(order, 0, 1 + deckRng.nextInt(order.size - 1))
        return if (deck.holds.isEmpty()) order else holdBack(order, deck.holds)
    }

    /**
     * Moves each held movie back until the deck has run its hold, counting the deck's own
     * airtime (programs between its cards only add time); the rest keep their order. When
     * nothing is ready, the movie ready soonest goes.
     */
    private fun holdBack(order: IntArray, holds: List<DeckHold>): IntArray {
        val readyAfter = HashMap<Long, Long>(holds.size * 2).apply { for (hold in holds) put(hold.id, hold.readyAfterMs) }
        val result = IntArray(order.size)
        val waiting = ArrayList<Int>()
        var filled = 0
        var next = 0
        var elapsed = 0L
        while (filled < order.size) {
            val ready = waiting.indexOfFirst { readyAfter.getValue(pool.ids[it]) <= elapsed }
            val index = when {
                ready >= 0 -> waiting.removeAt(ready)
                next < order.size -> {
                    val card = order[next++]
                    if ((readyAfter[pool.ids[card]] ?: 0L) > elapsed) {
                        waiting += card
                        continue
                    }
                    card
                }
                else -> waiting.removeAt(waiting.indices.minBy { readyAfter.getValue(pool.ids[waiting[it]]) })
            }
            result[filled++] = index
            elapsed += pool.durations[index]
        }
        return result
    }

    /**
     * A show picked by weight from [shows] ([weights] cumulative), leaving [exclude] out; -1
     * when none is left.
     */
    private fun draw(shows: IntArray, weights: DoubleArray, exclude: Int): Int {
        if (shows.isEmpty()) return -1
        val skipped = shows.indexOf(exclude)
        val skippedWeight = if (skipped < 0) 0.0 else weights[skipped] - (if (skipped > 0) weights[skipped - 1] else 0.0)
        val total = weights.last() - skippedWeight
        if (!(total > 0.0)) return -1
        val target = rng.nextDouble() * total
        // The skipped show's range has no width, so the search never lands on it.
        fun upTo(k: Int) = if (skipped in 0..k) weights[k] - skippedWeight else weights[k]
        var low = 0
        var high = shows.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (upTo(mid) > target) high = mid else low = mid + 1
        }
        return shows[low]
    }

    /**
     * The series' first eligible episode at or after its cursor, wrapping around. A series
     * without a cursor has not aired yet and starts at its [openingEpisode].
     */
    private fun peekEpisode(show: Int): Int {
        val from = pool.showStart[show]
        val to = pool.showEnd[show]
        val position = pool.showKeys[show]?.let { showCursors[it] }
        val start = if (position == null) {
            openingEpisode(show)
        } else {
            // First item at or after the cursor (binary search over the series' sorted range).
            var low = from
            var high = to
            while (low < high) {
                val mid = (low + high) ushr 1
                if (pool.compareKey(mid, position) < 0) low = mid + 1 else high = mid
            }
            low
        }
        for (i in start until to) if (eligible.itemMask[i]) return i
        for (i in from until start) if (eligible.itemMask[i]) return i
        error("Eligible series without an eligible episode")
    }

    /**
     * Where a series opens on this channel: one of its regular episodes, picked from the seed
     * and the series, so channels don't all open at episode 1. Specials and unnumbered episodes
     * (sorted last) only open a series that has nothing else.
     */
    private fun openingEpisode(show: Int): Int {
        val from = pool.showStart[show]
        var low = from
        var high = pool.showEnd[show]
        while (low < high) {
            val mid = (low + high) ushr 1
            if (pool.seasonKeys[mid] < CandidatePool.SPECIALS_SEASON) low = mid + 1 else high = mid
        }
        val count = (if (low > from) low else pool.showEnd[show]) - from
        return from + Math.floorMod(Fingerprint().add(config.seed).add(pool.showKeys[show]).value, count.toLong()).toInt()
    }

    /** Moves the series' cursor past [index]. */
    private fun takeEpisode(show: Int, index: Int) {
        val key = pool.showKeys[show] ?: return
        showCursors[key] = EpisodeKey(pool.seasonKeys[index], pool.episodeKeys[index], pool.ids[index] + 1)
    }

    /**
     * The first eligible movie at or after the release cursor that [level] allows, wrapping
     * around, or -1; moves the cursor past it. Before the first one, the release order opens at
     * a movie picked from the seed, so channels don't all open with the oldest.
     */
    private fun nextMovie(level: Int): Int {
        val movies = eligible.movies
        if (movies.isEmpty()) return -1
        val position = movieCursor
        val start = if (position == null) {
            Math.floorMod(Fingerprint().add(config.seed).add(MOVIES).value, movies.size.toLong()).toInt()
        } else {
            var low = 0
            var high = movies.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (pool.compareRelease(movies[mid], position) < 0) low = mid + 1 else high = mid
            }
            low
        }
        for (k in movies.indices) {
            val index = movies[(start + k) % movies.size]
            if (allowed(index, level)) {
                movieCursor = ReleaseKey(pool.releaseKeys[index], pool.ids[index] + 1)
                return index
            }
        }
        return -1
    }

    /** Off air until the next daypart edge or local midnight (when the lineup can change). */
    private fun offAir(local: Long) {
        val start = cursor
        val msOfDay = Math.floorMod(local, DAY_MS)
        var end = start + (DAY_MS - msOfDay)
        for (d in inputs.dayparts) {
            end = minOf(end, edgeAfter(start, msOfDay, d.startMinuteOfDay), edgeAfter(start, msOfDay, d.endMinuteOfDay))
        }
        out.offAir(start, end)
        cursor = end
    }

    private fun edgeAfter(start: Long, msOfDay: Long, minute: Int): Long {
        var delta = minute * MINUTE_MS - msOfDay
        if (delta <= 0) delta += DAY_MS
        return start + delta
    }

    private companion object {
        /** Repeat rules a pick keeps: both, only "not right after itself", or none. */
        const val STRICT = 0
        const val RELAXED = 1
        const val ANY = 2
    }
}

/** Keep the stagger and the movies' opening apart from other hashes of the same values. */
private const val STAGGER = 1L
private const val MOVIES = 2L

private fun shuffle(values: IntArray, rng: SplitMix64) {
    for (i in values.size - 1 downTo 1) swap(values, i, rng.nextInt(i + 1))
}

private fun swap(values: IntArray, a: Int, b: Int) {
    val t = values[a]
    values[a] = values[b]
    values[b] = t
}
