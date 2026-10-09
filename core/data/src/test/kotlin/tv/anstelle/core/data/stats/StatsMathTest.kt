package tv.anstelle.core.data.stats

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsMathTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val manila = ZoneId.of("Asia/Manila")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(zone: ZoneId, date: String, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, minute), zone).toInstant().toEpochMilli()

    @Test
    fun `a view lands in the local hours it ran through`() {
        val hours = LongArray(24)
        StatsMath.addHours(at(manila, "2026-10-09", 20, 40), 90 * minute, manila, hours)
        assertEquals(20 * minute, hours[20])
        assertEquals(60 * minute, hours[21])
        assertEquals(10 * minute, hours[22])
        assertEquals(90 * minute, hours.sum())
    }

    @Test
    fun `a view across midnight wraps to the first hours`() {
        val hours = LongArray(24)
        StatsMath.addHours(at(manila, "2026-10-09", 23, 30), hour, manila, hours)
        assertEquals(30 * minute, hours[23])
        assertEquals(30 * minute, hours[0])
    }

    @Test
    fun `an hour the clocks repeat holds both`() {
        // Berlin's clocks go back from 03:00 to 02:00 on 2026-10-25.
        val hours = LongArray(24)
        StatsMath.addHours(at(berlin, "2026-10-25", 1, 30), 3 * hour, berlin, hours)
        assertEquals(30 * minute, hours[1])
        assertEquals(2 * hour, hours[2])
        assertEquals(30 * minute, hours[3])
    }

    @Test
    fun `an hour the clocks skip stays empty`() {
        // Berlin's clocks go forward from 02:00 to 03:00 on 2026-03-29.
        val hours = LongArray(24)
        StatsMath.addHours(at(berlin, "2026-03-29", 1, 30), hour, berlin, hours)
        assertEquals(30 * minute, hours[1])
        assertEquals(0, hours[2])
        assertEquals(30 * minute, hours[3])
    }

    @Test
    fun `the peak hour has the most time`() {
        val hours = LongArray(24).also { it[9] = 5; it[21] = 7; it[22] = 7 }
        assertEquals(21, StatsMath.peakHour(hours))
        assertNull(StatsMath.peakHour(LongArray(24)))
    }

    @Test
    fun `streaks count days in a row`() {
        val today = 100L
        assertEquals(Streaks(current = 3, best = 4), StatsMath.streaks(listOf(80L, 81, 82, 83, 90, 98, 99, 100), today))
        assertEquals(Streaks(current = 2, best = 2), StatsMath.streaks(listOf(98L, 99), today), "a streak is alive through yesterday")
        assertEquals(Streaks(current = 0, best = 2), StatsMath.streaks(listOf(97L, 98), today))
        assertEquals(Streaks(current = 1, best = 1), StatsMath.streaks(listOf(100L), today))
        assertEquals(Streaks(0, 0), StatsMath.streaks(emptyList(), today))
    }

    @Test
    fun `views less than 5 minutes apart are one sitting`() {
        val starts = longArrayOf(0, 25 * minute, 49 * minute, 2 * hour)
        val durations = longArrayOf(22 * minute, 22 * minute, 22 * minute, 10 * minute)
        // 0–22, 25–47 and 49–71 run together; the next starts 49 minutes after.
        assertEquals(71 * minute, StatsMath.longestSitting(starts, durations))
    }

    @Test
    fun `a gap of 5 minutes ends the sitting`() {
        val starts = longArrayOf(0, 27 * minute)
        val durations = longArrayOf(22 * minute, 30 * minute)
        assertEquals(30 * minute, StatsMath.longestSitting(starts, durations))
        assertEquals(0, StatsMath.longestSitting(LongArray(0), LongArray(0)))
    }

    @Test
    fun `genres add up across items and keep their biggest item`() {
        val rows = listOf(
            tv.anstelle.core.database.dao.GenreRow(1, "|Comedy|Drama|", 10 * minute),
            tv.anstelle.core.database.dao.GenreRow(2, "|Drama|", 30 * minute),
            tv.anstelle.core.database.dao.GenreRow(3, "", 50 * minute),
            tv.anstelle.core.database.dao.GenreRow(4, "|Comedy|", 5 * minute),
        )
        assertContentEquals(
            listOf(GenreTotal("Drama", 40 * minute, 2), GenreTotal("Comedy", 15 * minute, 1)),
            topGenres(rows, limit = 5),
        )
        assertEquals(1, topGenres(rows, limit = 1).size)
    }
}
