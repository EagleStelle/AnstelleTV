package tv.anstelle.core.scheduler

import tv.anstelle.core.model.SlotKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

const val MIN = 60_000L
const val DAY = 24 * 60 * MIN

fun utc(zone: ZoneId, local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

fun local(zone: ZoneId, utcMs: Long): ZonedDateTime = Instant.ofEpochMilli(utcMs).atZone(zone)

/** [count] episodes of one show in season 1, ids `idBase + 1 ..`. */
fun show(
    key: String,
    idBase: Long,
    count: Int,
    durationMs: Long = 22 * MIN,
    weight: Float = 1f,
    rating: Int? = null,
): List<Candidate> = List(count) { e ->
    Candidate(
        id = idBase + e + 1,
        durationMs = durationMs,
        showKey = key,
        season = 1,
        episode = e + 1,
        weight = weight,
        ratingScore = rating,
    )
}

/** A movie released on January 1 of [year] (no release date when null). */
fun movie(id: Long, durationMs: Long, year: Int? = null, rating: Int? = null, weight: Float = 1f): Candidate = Candidate(
    id = id,
    durationMs = durationMs,
    weight = weight,
    ratingScore = rating,
    releaseUtcMs = year?.let { LocalDateTime.of(it, 1, 1, 0, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli() },
)

/** [count] movies without release dates, ids `idBase + 1 ..`. */
fun movies(idBase: Long, count: Int, durationMs: Long = 90 * MIN, rating: Int? = null, weight: Float = 1f): List<Candidate> =
    List(count) { movie(idBase + it + 1, durationMs, rating = rating, weight = weight) }

data class Row(val kind: SlotKind, val mediaItemId: Long?, val start: Long, val end: Long)

fun ScheduleSlots.rows(): List<Row> = List(size) { Row(kind(it), mediaItemId(it), startUtcMs(it), endUtcMs(it)) }

fun ScheduleSlots.programIds(): List<Long> = rows().mapNotNull { it.mediaItemId }

/**
 * Structural invariants of every schedule: rows are contiguous from [firstStart], and each
 * program row lasts exactly as long as its item.
 */
fun assertWellFormed(slots: ScheduleSlots, inputs: ScheduleInputs, firstStart: Long) {
    val durations = (listOf(inputs.pool) + inputs.seasons.map { it.pool })
        .flatMap { pool -> List(pool.size) { pool.id(it) to pool.durations[it] } }.toMap()
    var t = firstStart
    for (row in slots.rows()) {
        assertEquals(t, row.start, "gap or overlap at ${row.start}")
        assertTrue(row.end > row.start)
        if (row.kind == SlotKind.PROGRAM) {
            assertEquals(durations.getValue(row.mediaItemId!!), row.end - row.start, "program length")
        } else {
            assertEquals(null, row.mediaItemId)
        }
        t = row.end
    }
}
