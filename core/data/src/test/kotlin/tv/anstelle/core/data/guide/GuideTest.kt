package tv.anstelle.core.data.guide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import tv.anstelle.core.data.FakeScheduleDao
import tv.anstelle.core.data.lineUnderLogo
import tv.anstelle.core.data.programDetail
import tv.anstelle.core.data.stub
import tv.anstelle.core.database.dao.GuideAiring
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.dao.MediaServerDao
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.SlotKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GuideTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun limit(score: Int?) = ProfileAccess(1, score)

    private fun airing(
        channel: Long,
        start: Long,
        end: Long,
        item: Long?,
        show: String? = null,
        season: Int? = null,
        episode: Int? = null,
        showId: Long? = item,
        score: Int? = null,
    ) =
        GuideAiring(
            channelId = channel,
            kind = if (item == null) SlotKind.OFF_AIR else SlotKind.PROGRAM,
            mediaItemId = item,
            showId = showId,
            startUtcMs = start,
            endUtcMs = end,
            title = item?.let { "Episode $it" },
            showTitle = show,
            season = season,
            episode = episode,
            year = null,
            ratingScore = score,
        )

    @Test
    fun `back-to-back restricted programs become one cell, apart from what may be watched`() {
        val strip = ProgramStrip.fromRows(
            listOf(
                airing(1, 0, hour, 10, score = 16),
                airing(1, hour, 2 * hour, 11, score = 18),
                airing(1, 2 * hour, 3 * hour, 12),
                airing(1, 3 * hour, 4 * hour, 13, score = 7),
                airing(1, 4 * hour, 5 * hour, 14, score = 16),
                airing(1, 5 * hour, 6 * hour, null),
                airing(1, 6 * hour, 7 * hour, 15, score = 16),
            ),
            offAirTitle = "Off air",
        ).getValue(1)
        val merged = strip.mergeRestricted(limit(13))
        assertContentEquals(longArrayOf(0, 3 * hour, 4 * hour, 5 * hour, 6 * hour), merged.starts)
        assertContentEquals(longArrayOf(3 * hour, 4 * hour, 5 * hour, 6 * hour, 7 * hour), merged.ends)
        assertContentEquals(longArrayOf(10, 13, 14, 0, 15), merged.items)
        assertEquals(listOf(true, false, true, false, true), (0 until merged.size).map { merged.isRestricted(it, limit(13)) })
        assertSame(strip, strip.mergeRestricted(limit(null)))
        assertSame(strip, strip.mergeRestricted(limit(18)))
    }

    @Test
    fun `a rating limit restricts programs over it and unrated ones, never off air`() {
        val strip = ProgramStrip.fromRows(
            listOf(
                airing(1, 0, hour, 10, score = 7),
                airing(1, hour, 2 * hour, 11, score = 16),
                airing(1, 2 * hour, 3 * hour, 12),
                airing(1, 3 * hour, 4 * hour, null),
            ),
            offAirTitle = "Off air",
        ).getValue(1)
        assertEquals(listOf(false, true, true, false), (0 until strip.size).map { strip.isRestricted(it, limit(13)) })
        assertEquals(listOf(false, false, false, false), (0 until strip.size).map { strip.isRestricted(it, limit(null)) })
        val merged = ProgramStrip.merge(listOf(strip, ProgramStrip.fromRows(listOf(airing(1, 4 * hour, 5 * hour, 13, score = 18)), "Off air").getValue(1)))
        assertEquals(listOf(false, true, true, false, true), (0 until merged.size).map { merged.isRestricted(it, limit(13)) })
    }

    @Test
    fun `what the profile's Jellyfin user can't see is restricted at any rating, and joins a restricted stretch`() {
        val strip = ProgramStrip.fromRows(
            listOf(
                airing(1, 0, hour, 10, score = 7),
                airing(1, hour, 2 * hour, 11, score = 7),
                airing(1, 2 * hour, 3 * hour, 12, score = 16),
                airing(1, 3 * hour, 4 * hour, null),
            ),
            offAirTitle = "Off air",
        ).getValue(1)
        val hidden = ProfileAccess(1, null, listOf(11L))
        assertEquals(listOf(false, true, false, false), (0 until strip.size).map { strip.isRestricted(it, hidden) })
        val limited = ProfileAccess(1, 13, listOf(11L))
        val merged = strip.mergeRestricted(limited)
        assertContentEquals(longArrayOf(10, 11, 0), merged.items)
        assertContentEquals(longArrayOf(hour, 3 * hour, 4 * hour), merged.ends)
    }

    @Test
    fun `rows become one strip per channel with show titles and off-air cells`() {
        val strips = ProgramStrip.fromRows(
            listOf(
                airing(1, 0, 22 * minute, 10, show = "Show", season = 1, episode = 4),
                airing(1, 22 * minute, 2 * hour, null),
                airing(2, 0, 90 * minute, 20),
            ),
            offAirTitle = "Off air",
        )
        val one = strips.getValue(1)
        assertContentEquals(longArrayOf(0, 22 * minute), one.starts)
        assertEquals("Show", one.titles[0])
        assertEquals("S1E4 - Episode 10", one.details[0])
        assertEquals("Off air", one.titles[1])
        assertEquals(true, one.isOffAir(1))
        assertNull(one.details[1])
        assertEquals("Episode 20", strips.getValue(2).titles[0])
    }

    @Test
    fun `cells are found by time with binary search`() {
        val strip = ProgramStrip.fromRows(
            listOf(airing(1, 0, 30 * minute, 1), airing(1, 30 * minute, 50 * minute, 2), airing(1, 50 * minute, 2 * hour, 3)),
            "Off air",
        ).getValue(1)
        assertEquals(0, strip.indexAt(0))
        assertEquals(0, strip.indexAt(30 * minute - 1))
        assertEquals(1, strip.indexAt(30 * minute))
        assertEquals(2, strip.indexAt(119 * minute))
        assertEquals(-1, strip.indexAt(2 * hour))
        assertEquals(-1, strip.indexAt(-1))
        // Visible from 40 to 70 minutes: the second and third cells.
        assertEquals(1, strip.firstEndingAfter(40 * minute))
        assertEquals(3, strip.endOfStartsBefore(70 * minute))
        assertEquals(2, strip.endOfStartsBefore(50 * minute))
    }

    @Test
    fun `merging windows keeps a cell that spans both only once`() {
        val first = ProgramStrip.fromRows(listOf(airing(1, 0, 5 * hour, 1), airing(1, 5 * hour, 7 * hour, 2)), "Off air").getValue(1)
        val second = ProgramStrip.fromRows(listOf(airing(1, 5 * hour, 7 * hour, 2), airing(1, 7 * hour, 8 * hour, 3)), "Off air").getValue(1)
        val merged = ProgramStrip.merge(listOf(second, first))
        assertContentEquals(longArrayOf(0, 5 * hour, 7 * hour), merged.starts)
        assertContentEquals(longArrayOf(1, 2, 3), merged.items)
        assertContentEquals(longArrayOf(1, 2, 3), merged.shows)
        assertSame(first, ProgramStrip.merge(listOf(first, ProgramStrip.EMPTY)))
        assertSame(ProgramStrip.EMPTY, ProgramStrip.merge(emptyList()))
    }

    @Test
    fun `back-to-back airings of a show share a run color and the next show takes another`() {
        // Shows 100 (twice), 200, 300 (three times), off air, then 300 again, airing every 30 minutes.
        val shows = listOf(100L, 100L, 200L, 300L, 300L, 300L, null, 300L)
        val strip = ProgramStrip.fromRows(
            shows.mapIndexed { i, show ->
                airing(1, i * 30 * minute, (i + 1) * 30 * minute, show?.let { i + 1L }, showId = show)
            },
            "Off air",
        ).getValue(1)
        assertContentEquals(longArrayOf(100, 100, 200, 300, 300, 300, 0, 300), strip.shows)
        for (count in 2..6) {
            val runs = strip.runSlots(count)
            assertEquals(runs[0], runs[1])
            assertEquals(runs[3], runs[4])
            assertEquals(runs[4], runs[5])
            assertTrue(runs[1] != runs[2] && runs[2] != runs[3], "neighbouring shows differ with $count colors")
            assertEquals(-1, runs[6])
            assertTrue(runs.all { it in -1 until count })
        }
        // Every show in a long day stays apart from the one before it.
        val day = ProgramStrip.fromRows(
            (0 until 48).map { i -> airing(1, i * 30 * minute, (i + 1) * 30 * minute, i + 1L, showId = i / 2 * 7L % 5 + 1) },
            "Off air",
        ).getValue(1)
        val runs = day.runSlots(4)
        for (i in 1 until day.size) {
            if (day.shows[i] == day.shows[i - 1]) assertEquals(runs[i - 1], runs[i]) else assertTrue(runs[i - 1] != runs[i])
        }
    }

    @Test
    fun `a window holds every cell overlapping it, including one that began before`() = runTest {
        val dao = FakeScheduleDao()
        dao.insertSlots(
            listOf(
                ScheduleSlotEntity(channelId = 1, kind = SlotKind.PROGRAM, mediaItemId = 7, startUtcMs = 0, endUtcMs = 2 * hour),
                ScheduleSlotEntity(channelId = 1, kind = SlotKind.OFF_AIR, mediaItemId = null, startUtcMs = 2 * hour, endUtcMs = 9 * hour),
                ScheduleSlotEntity(channelId = 2, kind = SlotKind.PROGRAM, mediaItemId = 8, startUtcMs = 0, endUtcMs = hour),
            ),
        )
        val repository = GuideRepository(dao, stub<MediaItemDao>(), stub<MediaServerDao>(), LongestSlot(dao), Dispatchers.Unconfined)
        val window = repository.window(listOf(1, 2), fromUtcMs = hour, toUtcMs = 7 * hour)
        assertContentEquals(longArrayOf(0, 2 * hour), window.getValue(1).starts)
        assertEquals("Item 7", window.getValue(1).titles[0])
        assertEquals(GuideRepository.OFF_AIR_TITLE, window.getValue(1).titles[1])
        // Channel 2's program ended when the window starts.
        assertNull(window[2])
    }

    @Test
    fun `badges name the picture and the sound`() {
        assertEquals(listOf("4K", "DV", "Atmos", "7.1"), ProgramBadges.of(HdrType.DOLBY_VISION, 3840, 2160, "Atmos", 8))
        assertEquals(listOf("HDR10", "DTS", "5.1"), ProgramBadges.of(HdrType.HDR10, 1920, 1080, "DTS", 6))
        assertEquals(listOf("HLG"), ProgramBadges.of(HdrType.HLG, 720, 480, "AAC", 2))
        assertEquals(emptyList(), ProgramBadges.of(HdrType.SDR, null, null, null, null))
    }

    @Test
    fun `program details read like the guide`() {
        assertEquals("S2E3 - Pilot", programDetail("Pilot", "Show", 2, 3, 2001))
        assertEquals("Pilot", programDetail("Pilot", "Show", null, null, null))
        assertEquals("1999", programDetail("Movie", null, null, null, 1999))
        assertNull(programDetail("Movie", null, null, null, null))
        // Under a logo, a movie is named with its year; an episode's line stays as it is.
        assertEquals("Movie (1999)", lineUnderLogo("1999", "Movie", isEpisode = false, hasLogo = true))
        assertEquals("1999", lineUnderLogo("1999", "Movie", isEpisode = false, hasLogo = false))
        assertEquals("S2E3 - Pilot", lineUnderLogo("S2E3 - Pilot", "Show", isEpisode = true, hasLogo = true))
        assertEquals("Movie", lineUnderLogo(null, "Movie", isEpisode = false, hasLogo = true))
        assertEquals("22 min", GuideRepository.durationLabel(22 * minute))
        assertEquals("1 h 54 min", GuideRepository.durationLabel(114 * minute))
        assertEquals("2 h", GuideRepository.durationLabel(2 * hour))
        assertNull(GuideRepository.durationLabel(0))
    }
}
