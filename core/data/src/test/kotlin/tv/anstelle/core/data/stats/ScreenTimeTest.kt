package tv.anstelle.core.data.stats

import tv.anstelle.core.database.dao.DayTotal
import tv.anstelle.core.database.dao.ViewTime
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.WatchSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenTimeTest {
    private val minute = 60_000L
    private val manila = ZoneId.of("Asia/Manila")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val thursday = LocalDate.parse("2026-10-08")

    private fun at(zone: ZoneId, date: LocalDate, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant().toEpochMilli()

    private fun view(date: LocalDate, hour: Int, minutes: Long, source: WatchSource = WatchSource.CHANNEL, type: MediaType = MediaType.EPISODE, completed: Boolean = false) =
        ViewTime(at(manila, date, hour), minutes * minute, date.toEpochDay(), source, completed, type)

    @Test
    fun `windows follow the calendar and the locale's week`() {
        val monday = StatsWindow.of(StatsPeriod.WEEK, thursday, DayOfWeek.MONDAY)
        assertEquals(LocalDate.parse("2026-10-05") to LocalDate.parse("2026-10-11"), monday.first to monday.last)
        val sunday = StatsWindow.of(StatsPeriod.WEEK, thursday, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.parse("2026-10-04"), sunday.first)
        assertEquals(LocalDate.parse("2026-09-28"), monday.step(-1).first)

        val february = StatsWindow.of(StatsPeriod.MONTH, LocalDate.parse("2028-02-10"), DayOfWeek.MONDAY)
        assertEquals(29, february.bars)
        assertEquals(LocalDate.parse("2028-03-31"), february.step(1).last)

        val year = StatsWindow.of(StatsPeriod.YEAR, thursday, DayOfWeek.MONDAY)
        assertEquals(12, year.bars)
        assertEquals(9, year.barOf(thursday.toEpochDay()))
        assertEquals(-1, year.barOf(LocalDate.parse("2027-01-01").toEpochDay()))
        assertEquals(24, StatsWindow.of(StatsPeriod.DAY, thursday, DayOfWeek.MONDAY).bars)
    }

    @Test
    fun `a picked bar narrows what the lists cover`() {
        val week = StatsWindow.of(StatsPeriod.WEEK, thursday, DayOfWeek.MONDAY)
        assertEquals(StatsSpan(week.firstDay, week.lastDay), week.span(null, manila))
        assertEquals(StatsSpan(thursday.toEpochDay(), thursday.toEpochDay()), week.span(3, manila))

        val year = StatsWindow.of(StatsPeriod.YEAR, thursday, DayOfWeek.MONDAY)
        val february = year.span(1, manila)
        assertEquals(LocalDate.parse("2026-02-01").toEpochDay() to LocalDate.parse("2026-02-28").toEpochDay(), february.fromDay to february.toDay)

        val day = StatsWindow.of(StatsPeriod.DAY, thursday, DayOfWeek.MONDAY)
        val nine = day.span(21, manila)
        assertEquals(at(manila, thursday, 21) to at(manila, thursday, 22), nine.fromUtcMs to nine.toUtcMs)
        assertEquals(LocalDays.startUtcMs(thursday.toEpochDay() + 1, manila), day.span(23, manila).toUtcMs)
    }

    @Test
    fun `an hour the clocks skip covers nothing`() {
        val spring = StatsWindow.of(StatsPeriod.DAY, LocalDate.parse("2026-03-29"), DayOfWeek.MONDAY)
        val skipped = spring.span(2, berlin)
        assertEquals(skipped.fromUtcMs, skipped.toUtcMs)
        val autumn = StatsWindow.of(StatsPeriod.DAY, LocalDate.parse("2026-10-25"), DayOfWeek.MONDAY)
        val repeated = autumn.span(2, berlin)
        assertEquals(120 * minute, repeated.toUtcMs - repeated.fromUtcMs)
    }

    @Test
    fun `a week's bars split Live TV and On demand by day`() {
        val week = StatsWindow.of(StatsPeriod.WEEK, thursday, DayOfWeek.MONDAY)
        val monday = LocalDate.parse("2026-10-05")
        val views = listOf(
            view(monday, 20, 30),
            view(monday, 21, 45, WatchSource.ON_DEMAND, MediaType.MOVIE, completed = true),
            view(thursday, 9, 22, completed = true),
        )
        val days = listOf(monday.toEpochDay(), thursday.toEpochDay())
        val screen = buildScreenTime(week, views, days, listOf(DayTotal(thursday.toEpochDay(), 22 * minute)), thursday.toEpochDay(), manila)

        assertEquals(listOf(30L, 0, 0, 22, 0, 0, 0), screen.live.map { it / minute })
        assertEquals(listOf(45L, 0, 0, 0, 0, 0, 0), screen.onDemand.map { it / minute })
        assertEquals(22 * minute, screen.todayMs)
        val habits = screen.habits
        assertEquals(97 * minute, habits.totalMs)
        assertEquals(52 * minute, habits.liveMs)
        assertEquals(45 * minute, habits.onDemandMs)
        assertEquals(1, habits.episodesFinished)
        assertEquals(1, habits.moviesFinished)
        // Monday to Thursday so far: 4 days.
        assertEquals(97 * minute / 4, habits.dailyAverageMs)
        assertEquals(21, habits.peakHour)
        assertEquals(Streaks(current = 1, best = 1), habits.streaks)
        // 20:00–20:30 and 21:00–21:45 are 30 minutes apart.
        assertEquals(45 * minute, habits.longestSittingMs)
        assertEquals(monday.toEpochDay(), screen.firstDay)
    }

    @Test
    fun `a day's bars are its hours`() {
        val day = StatsWindow.of(StatsPeriod.DAY, thursday, DayOfWeek.MONDAY)
        val views = listOf(view(thursday, 20, 90), view(thursday, 22, 10, WatchSource.ON_DEMAND))
        val screen = buildScreenTime(day, views, listOf(thursday.toEpochDay()), emptyList(), thursday.toEpochDay(), manila)
        assertEquals(60 * minute, screen.live[20])
        assertEquals(30 * minute, screen.live[21])
        assertEquals(10 * minute, screen.onDemand[22])
        assertEquals(0, screen.onDemand[20])
        assertNull(screen.habits.dailyAverageMs, "a day has no daily average")
    }

    @Test
    fun `the week's average counts only days since the first one watched`() {
        val today = thursday.toEpochDay()
        val week = StatsWindow.of(StatsPeriod.WEEK, thursday, DayOfWeek.MONDAY)
        val recent = listOf(DayTotal(today - 2, 60 * minute), DayTotal(today - 1, 30 * minute), DayTotal(today, 5 * minute))
        val screen = buildScreenTime(week, emptyList(), listOf(today - 2, today - 1, today), recent, today, manila)
        assertEquals(45 * minute, screen.weekAverageMs)

        val longAgo = buildScreenTime(week, emptyList(), listOf(today - 30, today - 2), recent, today, manila)
        assertEquals(90 * minute / 7, longAgo.weekAverageMs)

        val firstDay = buildScreenTime(week, emptyList(), listOf(today), recent, today, manila)
        assertNull(firstDay.weekAverageMs, "nothing before today to average")
        assertNull(buildScreenTime(week, emptyList(), emptyList(), emptyList(), today, manila).firstDay)
    }

    @Test
    fun `a year's bars are its months`() {
        val year = StatsWindow.of(StatsPeriod.YEAR, thursday, DayOfWeek.MONDAY)
        val views = listOf(view(LocalDate.parse("2026-01-31"), 20, 30), view(LocalDate.parse("2026-02-01"), 20, 40), view(thursday, 20, 50))
        val screen = buildScreenTime(year, views, views.map { it.localDay }, emptyList(), thursday.toEpochDay(), manila)
        assertEquals(30 * minute, screen.live[0])
        assertEquals(40 * minute, screen.live[1])
        assertEquals(50 * minute, screen.live[9])
    }
}
