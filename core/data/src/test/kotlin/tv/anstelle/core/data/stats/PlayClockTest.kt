package tv.anstelle.core.data.stats

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayClockTest {
    private val minute = 60_000L
    private val manila = ZoneId.of("Asia/Manila")
    private val santiago = ZoneId.of("America/Santiago")

    private fun at(zone: ZoneId, date: String, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, minute), zone).toInstant().toEpochMilli()

    private fun day(date: String) = LocalDate.parse(date).toEpochDay()

    @Test
    fun `pauses and buffering don't count`() {
        val clock = PlayClock()
        val start = at(manila, "2026-10-09", 20)
        clock.set(true, start)
        clock.set(false, start + 10 * minute) // paused
        clock.set(true, start + 25 * minute)
        clock.set(false, start + 26 * minute) // buffering
        clock.set(true, start + 27 * minute)
        clock.stop(start + 40 * minute)
        assertEquals(24 * minute, clock.playedMs)
        assertEquals(listOf(DayShare(day("2026-10-09"), start, 24 * minute)), clock.byDay(manila))
    }

    @Test
    fun `repeated edges change nothing`() {
        val clock = PlayClock()
        val start = at(manila, "2026-10-09", 20)
        clock.set(true, start)
        clock.set(true, start + minute)
        clock.set(false, start + 5 * minute)
        clock.set(false, start + 6 * minute)
        clock.stop(start + 7 * minute)
        assertEquals(5 * minute, clock.playedMs)
    }

    @Test
    fun `a view that never played keeps nothing`() {
        val clock = PlayClock()
        clock.stop(at(manila, "2026-10-09", 20))
        assertEquals(0, clock.playedMs)
        assertEquals(emptyList(), clock.byDay(manila))
    }

    @Test
    fun `a view across midnight is split at local midnight`() {
        val clock = PlayClock()
        val start = at(manila, "2026-10-09", 23, 40)
        clock.set(true, start)
        clock.stop(start + 50 * minute)
        assertEquals(
            listOf(
                DayShare(day("2026-10-09"), start, 20 * minute),
                DayShare(day("2026-10-10"), at(manila, "2026-10-10", 0), 30 * minute),
            ),
            clock.byDay(manila),
        )
    }

    @Test
    fun `the next day's share starts where it played first`() {
        val clock = PlayClock()
        val start = at(manila, "2026-10-09", 23, 50)
        clock.set(true, start)
        clock.set(false, at(manila, "2026-10-10", 0, 5))
        clock.set(true, at(manila, "2026-10-10", 0, 20))
        clock.stop(at(manila, "2026-10-10", 0, 30))
        assertEquals(
            listOf(
                DayShare(day("2026-10-09"), start, 10 * minute),
                DayShare(day("2026-10-10"), at(manila, "2026-10-10", 0), 15 * minute),
            ),
            clock.byDay(manila),
        )
    }

    @Test
    fun `a day that skips midnight starts at its first hour`() {
        // Santiago's clocks go from 00:00 to 01:00 on 2026-09-06: that day starts at 01:00.
        val clock = PlayClock()
        val start = at(santiago, "2026-09-05", 23, 30)
        clock.set(true, start)
        clock.stop(start + 60 * minute) // 01:30 on the new day
        val split = clock.byDay(santiago)
        assertEquals(listOf(30 * minute, 30 * minute), split.map { it.playedMs })
        assertEquals(at(santiago, "2026-09-06", 1), split[1].startUtcMs)
        assertEquals(listOf(day("2026-09-05"), day("2026-09-06")), split.map { it.localDay })
    }

    @Test
    fun `a day with an hour repeated is 25 hours long`() {
        // Santiago's clocks go back from 00:00 to 23:00 on 2026-04-05, so 2026-04-04 runs on an hour.
        val clock = PlayClock()
        val start = at(santiago, "2026-04-04", 23, 30) // first 23:30, still on summer time
        clock.set(true, start)
        clock.stop(start + 150 * minute)
        val split = clock.byDay(santiago)
        assertEquals(listOf(90 * minute, 60 * minute), split.map { it.playedMs })
        assertEquals(listOf(day("2026-04-04"), day("2026-04-05")), split.map { it.localDay })
    }

    @Test
    fun `local days follow the zone, not UTC`() {
        val utcMidnight = at(ZoneId.of("UTC"), "2026-10-09", 0)
        assertEquals(day("2026-10-09"), LocalDays.of(utcMidnight, manila))
        assertEquals(day("2026-10-08"), LocalDays.of(utcMidnight, santiago))
        assertEquals(at(manila, "2026-10-09", 0), LocalDays.startUtcMs(day("2026-10-09"), manila))
    }
}
