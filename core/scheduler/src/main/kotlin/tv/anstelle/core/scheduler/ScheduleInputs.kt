package tv.anstelle.core.scheduler

import tv.anstelle.core.model.PlaybackOrder
import java.time.ZoneId

/** The channel settings the engine reads. */
class ChannelConfig(
    /** Schedule seed; the same seed and inputs always give the same schedule. */
    val seed: Long,
    val playbackOrder: PlaybackOrder,
    /** Episodes per show block for [PlaybackOrder.BLOCKS] (1–4). */
    val blockSize: Int = 1,
) {
    init {
        require(blockSize in 1..4) { "Block size must be 1–4: $blockSize" }
    }
}

/** A month/day range, both ends inclusive. A range that ends before it starts wraps the year (Dec 20 – Jan 5). */
data class MonthDayRange(val startMonth: Int, val startDay: Int, val endMonth: Int, val endDay: Int) {
    init {
        require(startMonth in 1..12 && endMonth in 1..12) { "Month out of range" }
        require(startDay in 1..31 && endDay in 1..31) { "Day out of range" }
    }

    fun contains(month: Int, day: Int): Boolean {
        val start = startMonth * 100 + startDay
        val end = endMonth * 100 + endDay
        val x = month * 100 + day
        return if (start <= end) x in start..end else x >= start || x <= end
    }
}

/**
 * A seasonal lineup: on local dates inside [range] the channel airs [pool] instead of its
 * base pool. [key] identifies the lineup in the engine state and must not be 0 (the base pool).
 * Seasons that only control when a channel is visible are not engine inputs.
 */
class SeasonalPool(val key: Long, val range: MonthDayRange, val pool: CandidatePool) {
    init {
        require(key != BASE_POOL_KEY) { "Season key 0 is reserved for the base pool" }
    }
}

/**
 * Time-of-day rule. Programs that start inside the window must have a rating score within
 * the limits and, when [allowedItemIds] is set, be one of those items. Unrated items never
 * pass a rating limit, matching the SQL `ratingScore <= :max` used for kid profiles.
 *
 * Minutes count from local midnight. The window is `[start, end)` and wraps midnight when
 * the end is before the start (22:00–06:00). Equal start and end mean the whole day.
 */
class Daypart(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val minRatingScore: Int? = null,
    val maxRatingScore: Int? = null,
    allowedItemIds: LongArray? = null,
) {
    private val allowed: LongArray? = allowedItemIds?.copyOf()?.also { it.sort() }

    init {
        require(startMinuteOfDay in 0 until MINUTES_PER_DAY && endMinuteOfDay in 0 until MINUTES_PER_DAY) {
            "Minute of day out of range"
        }
    }

    fun contains(minuteOfDay: Int): Boolean = when {
        startMinuteOfDay == endMinuteOfDay -> true
        startMinuteOfDay < endMinuteOfDay -> minuteOfDay in startMinuteOfDay until endMinuteOfDay
        else -> minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
    }

    internal fun allows(pool: CandidatePool, index: Int): Boolean {
        val rating = pool.ratings[index]
        if (minRatingScore != null && (rating == CandidatePool.NO_RATING || rating < minRatingScore)) return false
        if (maxRatingScore != null && (rating == CandidatePool.NO_RATING || rating > maxRatingScore)) return false
        return allowed == null || allowed.binarySearch(pool.ids[index]) >= 0
    }

    internal fun addTo(fingerprint: Fingerprint) {
        fingerprint.add(startMinuteOfDay).add(endMinuteOfDay)
            .add(minRatingScore ?: Int.MIN_VALUE).add(maxRatingScore ?: Int.MIN_VALUE)
        if (allowed == null) {
            fingerprint.add(-1)
        } else {
            fingerprint.add(allowed.size)
            for (id in allowed) fingerprint.add(id)
        }
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * Everything one channel's schedule depends on. Seasons and dayparts are checked in list
 * order and the first match wins; they are evaluated at each program's local start time.
 */
class ScheduleInputs(
    val config: ChannelConfig,
    val pool: CandidatePool,
    val seasons: List<SeasonalPool> = emptyList(),
    val dayparts: List<Daypart> = emptyList(),
    val zone: ZoneId,
) {
    /**
     * Stable hash of all inputs, stored as `schedule_state.inputsHash`. It is never 0,
     * because 0 marks a schedule as stale.
     */
    fun fingerprint(): Long {
        val f = Fingerprint()
        f.add(config.seed).add(config.playbackOrder.code).add(config.blockSize)
        f.add(pool.fingerprint)
        f.add(seasons.size)
        for (s in seasons) {
            f.add(s.key).add(s.range.startMonth).add(s.range.startDay).add(s.range.endMonth).add(s.range.endDay)
            f.add(s.pool.fingerprint)
        }
        f.add(dayparts.size)
        for (d in dayparts) d.addTo(f)
        f.add(zone.id)
        return f.value.takeIf { it != 0L } ?: 1L
    }
}

internal const val BASE_POOL_KEY = 0L
