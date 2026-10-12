package tv.anstelle.core.data.channels

import kotlinx.coroutines.test.runTest
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.FakeScheduleDao
import tv.anstelle.core.data.ScheduleRepository
import tv.anstelle.core.data.episodes
import tv.anstelle.core.data.item
import tv.anstelle.core.data.stub
import tv.anstelle.core.database.dao.CatalogRow
import tv.anstelle.core.database.dao.ChannelDao
import tv.anstelle.core.database.dao.MediaItemDao
import tv.anstelle.core.database.entity.ChannelDaypartEntity
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.model.SeasonMode
import tv.anstelle.core.model.SlotKind
import tv.anstelle.core.scheduler.ScheduleEngine
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

private fun channel(
    id: Long = 1,
    series: PlaybackOrder = PlaybackOrder.EPISODE_ORDER,
    movies: PlaybackOrder = PlaybackOrder.SHUFFLE,
    oncePerDay: Boolean = true,
    hidden: Boolean = false,
) = ChannelEntity(
    id = id, number = id.toInt(), name = "Channel $id", logoUri = null, kind = ChannelKind.GENERATED, seed = 42,
    seriesOrder = series, seriesBlock = 1, movieOrder = movies, movieBlock = 1, moviesOncePerDay = oncePerDay,
    isHidden = hidden, isFavorite = false, groupId = 1, autoKey = null, createdAtUtcMs = 0, updatedAtUtcMs = 0,
)

/** [count] two-hour movies with the genre Western, ids `idBase + 1 ..`. */
private fun westerns(idBase: Long, count: Int) =
    (1..count).map { item(idBase + it, type = MediaType.MOVIE, genres = listOf("Western"), minutes = 120, year = 1950 + it) }

private fun genres(channelId: Long, vararg genres: String, group: Int = 0, seasonId: Long? = null) =
    LineupRows.encode(channelId, seasonId, Lineup(listOf(RuleGroup(listOf(Rule(RuleField.GENRE, RuleOp.ANY_OF, genres.toList()))))), firstGroup = group)

/** Comedy (ids 1–40, rated 10), drama (101–140, rated 14), kids (201–210, rated 0), and a Christmas movie. */
private val library: List<CatalogRow> =
    episodes("comedy", 0, 40, genres = listOf("Comedy"), score = 10) +
        episodes("drama", 100, 40, genres = listOf("Drama"), score = 14, minutes = 44) +
        episodes("kids", 200, 10, genres = listOf("Kids"), score = 0, minutes = 11) +
        item(301, type = MediaType.MOVIE, title = "A Christmas Movie", genres = listOf("Family"), score = 0, minutes = 90)

class ChannelInputsTest {
    private val catalog = Catalog(library)

    private fun ids(pool: tv.anstelle.core.scheduler.CandidatePool) = List(pool.size) { pool.id(it) }.toSet()

    @Test
    fun `the base lineup becomes the pool, whatever it is rated`() {
        val setup = ChannelSetup(channel(), genres(1, "Comedy", "Drama"), emptyList(), emptyList())
        assertEquals((1L..40L).toSet() + (101L..140L), ids(ChannelInputs.build(setup, catalog, ZoneOffset.UTC).pool))
    }

    @Test
    fun `override seasons become seasonal pools and visibility seasons are ignored`() {
        val override = ChannelSeasonEntity(id = 3, channelId = 1, name = "Holidays", mode = SeasonMode.OVERRIDE_RULES, startMonth = 12, startDay = 1, endMonth = 12, endDay = 26)
        val visible = override.copy(id = 4, mode = SeasonMode.VISIBLE_IN_RANGE)
        val rules = genres(1, "Comedy") + LineupRows.encode(1, 3, Lineup(listOf(RuleGroup(listOf(Rule(RuleField.KEYWORD, RuleOp.ANY_OF, "christmas"))))))
        val inputs = ChannelInputs.build(ChannelSetup(channel(), rules, listOf(override, visible), emptyList()), catalog, ZoneOffset.UTC)
        assertEquals((1L..40L).toSet(), ids(inputs.pool))
        assertEquals(listOf(3L), inputs.seasons.map { it.key })
        assertEquals(setOf(301L), ids(inputs.seasons.single().pool))
    }

    @Test
    fun `a daypart's rule group only filters inside its window`() {
        val rules = genres(1, "Comedy", "Kids") + genres(1, "Kids", group = 7)
        // 06:00–09:00: kids shows only.
        val daypart = ChannelDaypartEntity(channelId = 1, name = "Morning", startMinuteOfDay = 360, endMinuteOfDay = 540, maxRatingScore = null, minRatingScore = null, ruleLogicGroup = 7)
        val inputs = ChannelInputs.build(ChannelSetup(channel(), rules, emptyList(), listOf(daypart)), catalog, ZoneOffset.UTC)
        assertEquals((1L..40L).toSet() + (201L..210L), ids(inputs.pool))

        val start = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
        val slots = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + DAY).slots
        for (i in 0 until slots.size) {
            val minute = ((slots.startUtcMs(i) - start) / MINUTE).toInt()
            val id = slots.mediaItemId(i) ?: continue
            if (minute in 360 until 540) assertTrue(id in 201L..210L, "comedy at minute $minute")
        }
    }

    @Test
    fun `the fingerprint follows rules and settings`() {
        fun hash(rules: List<ChannelRuleEntity>, entity: ChannelEntity = channel()) =
            ChannelInputs.build(ChannelSetup(entity, rules, emptyList(), emptyList()), catalog, ZoneOffset.UTC).fingerprint()
        val comedy = hash(genres(1, "Comedy"))
        assertEquals(comedy, hash(genres(1, "comedy")))
        assertNotEquals(comedy, hash(genres(1, "Drama")))
        assertNotEquals(comedy, hash(genres(1, "Comedy"), channel(series = PlaybackOrder.SHUFFLE)))
        assertNotEquals(comedy, hash(genres(1, "Comedy"), channel(movies = PlaybackOrder.EPISODE_ORDER)))
    }

    @Test
    fun `movies keep to once a day only when the lineup can fill a day`() {
        fun gap(rows: List<CatalogRow>, rules: List<ChannelRuleEntity>, oncePerDay: Boolean = true) =
            ChannelInputs.build(ChannelSetup(channel(oncePerDay = oncePerDay), rules, emptyList(), emptyList()), Catalog(rows), ZoneOffset.UTC).config.movieGapMs
        // Twelve two-hour movies fill a day; eleven don't, unless series air between them.
        assertEquals(OncePerDay.GAP_MS, gap(westerns(500, 12), genres(1, "Western")))
        assertEquals(0L, gap(westerns(500, 11), genres(1, "Western")))
        assertEquals(OncePerDay.GAP_MS, gap(library + westerns(500, 11), genres(1, "Western", "Comedy")))
        assertEquals(0L, gap(westerns(500, 12), genres(1, "Western"), oncePerDay = false))
    }

    @Test
    fun `movies carry their release dates`() {
        val rows = westerns(500, 3) + item(600, type = MediaType.MOVIE, genres = listOf("Western"), minutes = 120)
        val inputs = ChannelInputs.build(ChannelSetup(channel(movies = PlaybackOrder.EPISODE_ORDER), genres(1, "Western"), emptyList(), emptyList()), Catalog(rows), ZoneOffset.UTC)
        val start = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
        val ids = ScheduleEngine.generate(inputs, ScheduleEngine.initialState(inputs, start), start + DAY).slots.let { slots -> List(slots.size) { slots.mediaItemId(it) } }
        // By year from wherever the order opens, the movie without one last.
        val order = listOf(501L, 502, 503, 600)
        val opening = order.indexOf(ids.first())
        assertEquals(List(6) { order[(opening + it) % order.size] }, ids.take(6))
    }
}

class ScheduleUpdaterTest {
    private var now = Instant.parse("2026-10-06T10:17:00Z").toEpochMilli()
    private val time = TimeSource { now }
    private val scheduleDao = FakeScheduleDao()
    private val channels = HashMap<Long, ChannelEntity>()
    private val rules = HashMap<Long, List<ChannelRuleEntity>>()
    private var rows: List<CatalogRow> = library

    private val channelDao = object : ChannelDao by stub() {
        override suspend fun all() = channels.values.sortedBy { it.id }
        override suspend fun byId(id: Long) = channels[id]
        override suspend fun rules(channelId: Long) = rules[channelId].orEmpty()
        override suspend fun seasons(channelId: Long) = emptyList<ChannelSeasonEntity>()
        override suspend fun dayparts(channelId: Long) = emptyList<ChannelDaypartEntity>()
    }
    private val itemDao = object : MediaItemDao by stub() {
        override suspend fun catalog() = rows
    }
    private val updater = ScheduleUpdater(channelDao, scheduleDao, itemDao, ScheduleRepository(scheduleDao, time), { ChannelWords.EMPTY }, time)

    private fun slots(channelId: Long = 1) = scheduleDao.slotsOf(channelId)

    private fun assertContiguous(slots: List<ScheduleSlotEntity>) {
        slots.zipWithNext { a, b -> assertEquals(a.endUtcMs, b.startUtcMs, "gap or overlap at ${Instant.ofEpochMilli(a.endUtcMs)}") }
    }

    private fun at(utcMs: Long) = slots().last { it.startUtcMs <= utcMs }

    @Test
    fun `schedules are created, kept and extended 30 days ahead`() = runTest {
        channels[1] = channel()
        rules[1] = genres(1, "Comedy")

        assertEquals(mapOf(ScheduleOutcome.CREATED to 1), updater.updateAll(ZoneOffset.UTC))
        val created = slots()
        // Caught midway, as if the channel had been on air for a while.
        assertTrue(created.first().startUtcMs <= now && now < created.first().endUtcMs)
        assertTrue(created.last().endUtcMs >= now + ScheduleUpdater.HORIZON_MS)
        assertContiguous(created)
        assertTrue(created.all { it.kind == SlotKind.PROGRAM && it.mediaItemId in 1L..40L })

        assertEquals(mapOf(ScheduleOutcome.UP_TO_DATE to 1), updater.updateAll(ZoneOffset.UTC))

        now += DAY
        assertEquals(mapOf(ScheduleOutcome.EXTENDED to 1), updater.updateAll(ZoneOffset.UTC))
        val extended = slots()
        assertEquals(created, extended.take(created.size), "extension keeps what was there")
        assertTrue(extended.size > created.size)
        assertTrue(extended.last().endUtcMs >= now + ScheduleUpdater.HORIZON_MS)
        assertContiguous(extended)
    }

    @Test
    fun `a new lineup keeps the current and next airings`() = runTest {
        channels[1] = channel()
        rules[1] = genres(1, "Comedy")
        updater.updateAll(ZoneOffset.UTC)
        now += 2 * HOUR
        val current = at(now)
        val next = slots().first { it.startUtcMs == current.endUtcMs }

        rules[1] = genres(1, "Drama")
        assertEquals(ScheduleOutcome.REGENERATED, updater.update(1, ZoneOffset.UTC))
        assertEquals(current, at(now))
        assertEquals(next, slots().first { it.startUtcMs == current.endUtcMs })
        val after = slots().filter { it.startUtcMs >= next.endUtcMs }
        assertTrue(after.isNotEmpty() && after.all { it.mediaItemId in 101L..140L })
        assertContiguous(slots())
        assertEquals(ScheduleOutcome.UP_TO_DATE, updater.update(1, ZoneOffset.UTC))
    }

    @Test
    fun `an airing whose item was deleted is replaced`() = runTest {
        channels[1] = channel()
        rules[1] = genres(1, "Comedy")
        updater.updateAll(ZoneOffset.UTC)
        now += 2 * HOUR
        val current = at(now)
        rows = library.filter { it.id != current.mediaItemId }

        assertEquals(ScheduleOutcome.REGENERATED, updater.update(1, ZoneOffset.UTC))
        val replaced = at(now)
        assertEquals(current.startUtcMs, replaced.startUtcMs)
        assertNotEquals(current.mediaItemId, replaced.mediaItemId)
        assertTrue(slots().none { it.mediaItemId == current.mediaItemId && it.startUtcMs >= current.startUtcMs })
        assertContiguous(slots())
    }

    @Test
    fun `a new lineup keeps movies a day apart across the change`() = runTest {
        rows = westerns(500, 15)
        channels[1] = channel()
        rules[1] = genres(1, "Western")
        updater.updateAll(ZoneOffset.UTC)
        now += 20 * HOUR

        channels[1] = channel(movies = PlaybackOrder.EPISODE_ORDER)
        assertEquals(ScheduleOutcome.REGENERATED, updater.update(1, ZoneOffset.UTC))
        assertContiguous(slots())
        val last = HashMap<Long, Long>()
        for (slot in slots()) {
            val id = slot.mediaItemId ?: continue
            last[id]?.let { assertTrue(slot.startUtcMs - it >= DAY, "$id again after ${(slot.startUtcMs - it) / HOUR} h") }
            last[id] = slot.startUtcMs
        }
    }

    @Test
    fun `hidden channels are not scheduled`() = runTest {
        channels[1] = channel(hidden = true)
        rules[1] = genres(1, "Comedy")
        assertEquals(mapOf(ScheduleOutcome.HIDDEN to 1), updater.updateAll(ZoneOffset.UTC))
        assertTrue(slots().isEmpty())
    }

    @Test
    fun `a schedule that ran out restarts now instead of filling the past`() = runTest {
        channels[1] = channel()
        rules[1] = genres(1, "Comedy")
        updater.updateAll(ZoneOffset.UTC)
        val lastEnd = slots().last().endUtcMs

        now += 40 * DAY
        assertEquals(mapOf(ScheduleOutcome.EXTENDED to 1), updater.updateAll(ZoneOffset.UTC))
        // The restart is caught midway too: its first airing runs across now.
        val restart = slots().first { it.startUtcMs >= lastEnd }
        assertTrue(restart.startUtcMs <= now && now < restart.endUtcMs)
        assertEquals(restart, at(now))
    }

    @Test
    fun `channels created together start at different points`() = runTest {
        for (id in 1L..20L) {
            channels[id] = channel(id).copy(seed = id)
            rules[id] = genres(id, "Comedy", "Drama")
        }
        updater.updateAll(ZoneOffset.UTC)
        val starts = (1L..20L).map { id -> slots(id).first().startUtcMs }
        assertTrue(starts.all { it <= now })
        assertTrue(starts.toSet().size > 15, "$starts")
        // Each opens its series at an episode of its own.
        val openings = (1L..20L).map { id -> slots(id).first().mediaItemId!! % 100 }
        assertTrue(openings.toSet().size > 10, "$openings")
    }

    @Test
    fun `an unreadable state starts over without overlapping what aired`() = runTest {
        channels[1] = channel()
        rules[1] = genres(1, "Comedy")
        updater.updateAll(ZoneOffset.UTC)
        now += 3 * HOUR
        scheduleDao.states[1] = scheduleDao.states.getValue(1).copy(stateBlob = byteArrayOf(0))

        assertEquals(mapOf(ScheduleOutcome.CREATED to 1), updater.updateAll(ZoneOffset.UTC))
        assertContiguous(slots())
        assertTrue(at(now).endUtcMs > now)
    }
}
