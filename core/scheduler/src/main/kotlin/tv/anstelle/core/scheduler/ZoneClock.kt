package tv.anstelle.core.scheduler

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val SECOND_MS = 1_000L
internal const val MINUTE_MS = 60 * SECOND_MS
internal const val DAY_MS = 24 * 60 * MINUTE_MS

/**
 * Local-time lookups for one zone. The UTC offset is cached until the zone's next
 * transition, so the per-program lookups of a run allocate nothing between DST changes.
 */
internal class ZoneClock(zone: ZoneId) {
    private val rules = zone.rules
    private val fixed = rules.isFixedOffset
    private var offset = if (fixed) rules.getOffset(Instant.EPOCH).totalSeconds * SECOND_MS else 0L
    private var validFrom = Long.MAX_VALUE
    private var validUntil = Long.MIN_VALUE

    private var cachedEpochDay = Long.MIN_VALUE
    private var cachedMonth = 0
    private var cachedDay = 0

    fun offsetMs(utcMs: Long): Long {
        if (fixed || (utcMs >= validFrom && utcMs < validUntil)) return offset
        val instant = Instant.ofEpochMilli(utcMs)
        offset = rules.getOffset(instant).totalSeconds * SECOND_MS
        validFrom = utcMs
        validUntil = rules.nextTransition(instant)?.toEpochSecond()?.times(SECOND_MS) ?: Long.MAX_VALUE
        return offset
    }

    fun localMs(utcMs: Long): Long = utcMs + offsetMs(utcMs)

    /** Local minute of the day (0–1439) for a [localMs] value. */
    fun minuteOfDay(localMs: Long): Int = (Math.floorMod(localMs, DAY_MS) / MINUTE_MS).toInt()

    /** Local month (1–12) for a [localMs] value. The date is cached per day. */
    fun month(localMs: Long): Int {
        cache(localMs)
        return cachedMonth
    }

    fun dayOfMonth(localMs: Long): Int {
        cache(localMs)
        return cachedDay
    }

    private fun cache(localMs: Long) {
        val epochDay = Math.floorDiv(localMs, DAY_MS)
        if (epochDay == cachedEpochDay) return
        val date = LocalDate.ofEpochDay(epochDay)
        cachedEpochDay = epochDay
        cachedMonth = date.monthValue
        cachedDay = date.dayOfMonth
    }
}
