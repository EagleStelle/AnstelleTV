package tv.anstelle.core.scheduler

import tv.anstelle.core.model.PlaybackOrder

class ScheduleResult(val slots: ScheduleSlots, val state: EngineState)

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
 * - **Playback orders:** SHUFFLE plays a deck (every eligible item once per deck, and no
 *   repeat across deck boundaries). EPISODE_ORDER picks a show by weight and plays its next
 *   episode. BLOCKS does the same, but plays up to `blockSize` consecutive episodes of a show.
 */
object ScheduleEngine {
    /** A fresh state whose first program starts at [startUtcMs]. */
    fun initialState(inputs: ScheduleInputs, startUtcMs: Long): EngineState =
        EngineState(nextStartUtcMs = startUtcMs, rngState = inputs.config.seed)

    /**
     * Generates programs from `state.nextStartUtcMs` until one starts at or after [untilUtcMs].
     * The last airing may run past [untilUtcMs]; the returned state continues after it.
     */
    fun generate(inputs: ScheduleInputs, state: EngineState, untilUtcMs: Long): ScheduleResult =
        Run(inputs, state).generate(untilUtcMs)

    /**
     * Prepares a state for regenerating from [cursorUtcMs] after the inputs changed.
     * [removedItemIds] are the programs of the discarded slots, in airing order: each show
     * resumes at the first of its episodes that was discarded, so no episode is skipped.
     * A running block ends. Shuffle decks keep their position.
     */
    fun rewind(inputs: ScheduleInputs, state: EngineState, cursorUtcMs: Long, removedItemIds: LongArray): EngineState {
        val cursors = HashMap(state.showCursors)
        val rewound = HashSet<String>()
        val pools = listOf(inputs.pool) + inputs.seasons.map { it.pool }
        for (id in removedItemIds) {
            for (pool in pools) {
                val i = pool.indexOfId(id)
                if (i < 0) continue
                val show = pool.showKeys[pool.showOf[i]]
                if (show != null && rewound.add(show)) {
                    cursors[show] = EpisodeKey(pool.seasonKeys[i], pool.episodeKeys[i], pool.ids[i])
                }
                break
            }
        }
        return state.copy(nextStartUtcMs = cursorUtcMs, showCursors = cursors, blockShow = null, blockRemaining = 0)
    }
}

/** The eligible part of a lineup under one daypart. */
private class Eligible(pool: CandidatePool, daypart: Daypart?) {
    val itemMask = BooleanArray(pool.size) { daypart == null || daypart.allows(pool, it) }
    val items: IntArray = pool.ids.indices.filter { itemMask[it] }.toIntArray()
    val showMask = BooleanArray(pool.showCount)
    val shows: IntArray
    val cumulativeWeights: DoubleArray
    val fingerprint: Long

    init {
        for (i in items) showMask[pool.showOf[i]] = true
        shows = showMask.indices.filter { showMask[it] }.toIntArray()
        var total = 0.0
        cumulativeWeights = DoubleArray(shows.size) { k ->
            total += pool.showWeights[shows[k]]
            total
        }
        fingerprint = Fingerprint().apply {
            add(items.size)
            for (i in items) add(pool.ids[i])
        }.value
    }
}

private class Run(private val inputs: ScheduleInputs, state: EngineState) {
    private val config = inputs.config
    private val zone = ZoneClock(inputs.zone)
    private val rng = SplitMix64(state.rngState)
    private var cursor = state.nextStartUtcMs
    private val showCursors = HashMap(state.showCursors)
    private val decks = HashMap(state.decks)
    private var blockShow = state.blockShow
    private var blockRemaining = state.blockRemaining
    private val out = SlotBuffer()

    private val eligibleCache = HashMap<DeckKey, Eligible>()
    private val deckOrders = HashMap<DeckKey, IntArray>()

    fun generate(untilUtcMs: Long): ScheduleResult {
        while (cursor < untilUtcMs) step()
        val state = EngineState(
            nextStartUtcMs = cursor,
            rngState = rng.state,
            showCursors = showCursors,
            decks = decks,
            blockShow = blockShow,
            blockRemaining = blockRemaining,
        )
        return ScheduleResult(out.build(), state)
    }

    private fun step() {
        val local = zone.localMs(cursor)
        val seasonIndex = seasonAt(local)
        val pool = if (seasonIndex < 0) inputs.pool else inputs.seasons[seasonIndex].pool
        val poolKey = if (seasonIndex < 0) BASE_POOL_KEY else inputs.seasons[seasonIndex].key
        val minute = zone.minuteOfDay(local)
        val daypartIndex = inputs.dayparts.indexOfFirst { it.contains(minute) }
        val key = DeckKey(poolKey, daypartIndex)
        val eligible = eligibleCache.getOrPut(key) { Eligible(pool, inputs.dayparts.getOrNull(daypartIndex)) }

        val index = when (config.playbackOrder) {
            PlaybackOrder.SHUFFLE -> pickShuffle(key, pool, eligible)
            PlaybackOrder.EPISODE_ORDER -> pickShow(eligible).let { show ->
                if (show < 0) -1 else nextEpisode(pool, show, eligible)
            }
            PlaybackOrder.BLOCKS -> pickBlock(pool, eligible)
        }
        if (index < 0) {
            offAir(local)
        } else {
            val end = cursor + pool.durations[index]
            out.program(pool.ids[index], cursor, end)
            cursor = end
        }
    }

    private fun seasonAt(local: Long): Int {
        if (inputs.seasons.isEmpty()) return -1
        val month = zone.month(local)
        val day = zone.dayOfMonth(local)
        return inputs.seasons.indexOfFirst { it.range.contains(month, day) }
    }

    private fun pickShuffle(key: DeckKey, pool: CandidatePool, eligible: Eligible): Int {
        val n = eligible.items.size
        if (n == 0) return -1
        val stored = decks[key]
        val deck = if (stored == null || stored.fingerprint != eligible.fingerprint || stored.position >= n) {
            val previous = stored?.lastId ?: EngineState.NO_ID
            deckOrders.remove(key)
            DeckState(eligible.fingerprint, rng.nextLong(), previous, 0, previous)
        } else {
            stored
        }
        val order = deckOrders.getOrPut(key) { deckOrder(deck, pool, eligible) }
        val index = order[deck.position]
        decks[key] = deck.copy(position = deck.position + 1, lastId = pool.ids[index])
        return index
    }

    /** The deck's order, rebuilt from its seed. It never opens with the item that ended the previous deck. */
    private fun deckOrder(deck: DeckState, pool: CandidatePool, eligible: Eligible): IntArray {
        val order = eligible.items.copyOf()
        val deckRng = SplitMix64(deck.seed)
        shuffle(order, deckRng)
        if (order.size > 1 && pool.ids[order[0]] == deck.prevId) swap(order, 0, 1 + deckRng.nextInt(order.size - 1))
        return order
    }

    /** A show picked by weight among the shows with an eligible item, or -1. */
    private fun pickShow(eligible: Eligible): Int {
        val weights = eligible.cumulativeWeights
        if (weights.isEmpty()) return -1
        val target = rng.nextDouble() * weights.last()
        var low = 0
        var high = weights.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (weights[mid] > target) high = mid else low = mid + 1
        }
        return eligible.shows[low]
    }

    private fun pickBlock(pool: CandidatePool, eligible: Eligible): Int {
        val running = blockShow
        if (blockRemaining > 0 && running != null) {
            val show = pool.showIndexOf(running)
            if (show >= 0 && eligible.showMask[show]) {
                blockRemaining--
                return nextEpisode(pool, show, eligible)
            }
        }
        val show = pickShow(eligible)
        if (show < 0) return -1
        val key = pool.showKeys[show]
        blockShow = key
        val length = pool.showEnd[show] - pool.showStart[show]
        blockRemaining = if (key == null) 0 else minOf(config.blockSize, length) - 1
        return nextEpisode(pool, show, eligible)
    }

    /** The show's first eligible episode at or after its cursor, wrapping around; advances the cursor. */
    private fun nextEpisode(pool: CandidatePool, show: Int, eligible: Eligible): Int {
        val from = pool.showStart[show]
        val to = pool.showEnd[show]
        val key = pool.showKeys[show]
        val position = key?.let { showCursors[it] }
        var start = from
        if (position != null) {
            // First item at or after the cursor (binary search over the show's sorted range).
            var low = from
            var high = to
            while (low < high) {
                val mid = (low + high) ushr 1
                if (pool.compareKey(mid, position) < 0) low = mid + 1 else high = mid
            }
            start = low
        }
        var index = -1
        for (i in start until to) {
            if (eligible.itemMask[i]) {
                index = i
                break
            }
        }
        if (index < 0) {
            for (i in from until start) {
                if (eligible.itemMask[i]) {
                    index = i
                    break
                }
            }
        }
        check(index >= 0) { "Eligible show without an eligible episode" }
        if (key != null) {
            showCursors[key] = EpisodeKey(pool.seasonKeys[index], pool.episodeKeys[index], pool.ids[index] + 1)
        }
        return index
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
}

private fun shuffle(values: IntArray, rng: SplitMix64) {
    for (i in values.size - 1 downTo 1) swap(values, i, rng.nextInt(i + 1))
}

private fun swap(values: IntArray, a: Int, b: Int) {
    val t = values[a]
    values[a] = values[b]
    values[b] = t
}
