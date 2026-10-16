package tv.anstelle.core.data.stats

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.stub
import tv.anstelle.core.database.dao.SettingDao
import tv.anstelle.core.database.dao.WatchEventDao
import tv.anstelle.core.database.entity.SettingEntity
import tv.anstelle.core.database.entity.WatchEventEntity
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.WatchSource
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardHistoryTest {
    private val minute = 60_000L
    private val manila = ZoneId.of("Asia/Manila")
    private val now = ZonedDateTime.of(LocalDate.parse("2026-10-09").atTime(21, 0), manila).toInstant().toEpochMilli()
    private val today = LocalDate.parse("2026-10-09").toEpochDay()

    private val settingDao = FakeSettingDao()
    private val events = FakeWatchEventDao()
    private val history = DashboardHistory(events, SettingsRepository(settingDao), TimeSource { now })

    private fun view(profileId: Long = 1, played: List<Long> = listOf(22 * minute)) = FinishedView(
        profileId = profileId,
        source = WatchSource.CHANNEL,
        channelId = 7,
        mediaItemId = 42,
        title = "Pilot",
        showTitle = "Show",
        mediaType = MediaType.EPISODE,
        days = played.mapIndexed { i, ms -> DayShare(today + i, now + i * 60 * minute, ms) },
        completed = true,
    )

    @Test
    fun `a view is kept as one row per day, finished on its last`() = runTest {
        history.record(view(played = listOf(10 * minute, 15 * minute)))
        assertEquals(listOf(today, today + 1), events.rows.map { it.localDay })
        assertEquals(listOf(10 * minute, 15 * minute), events.rows.map { it.durationMs })
        assertEquals(listOf(false, true), events.rows.map { it.completed })
        assertTrue(events.rows.all { it.mediaType == MediaType.EPISODE && it.channelId == 7L && it.title == "Pilot" })
    }

    @Test
    fun `views under 30 seconds of playing aren't kept`() = runTest {
        history.record(view(played = emptyList()))
        history.record(view(played = listOf(29_999)))
        history.record(view(played = listOf(10_000, 19_999)))
        assertTrue(events.rows.isEmpty())
        history.record(view(played = listOf(10_000, 20_000)))
        assertEquals(2, events.rows.size)
    }

    @Test
    fun `a profile with the dashboard off keeps nothing`() = runTest {
        history.turnOff(1, keepHistory = true)
        history.record(view(profileId = 1))
        history.record(view(profileId = 2))
        assertEquals(listOf(2L), events.rows.map { it.profileId })
    }

    @Test
    fun `turning off and keeping leaves the history, and on resumes`() = runTest {
        history.record(view())
        history.turnOff(1, keepHistory = true)
        assertFalse(settingDao.values.value.getValue(Settings.dashboard(1).name).toBoolean())
        assertEquals(1, events.rows.size)
        history.turnOn(1)
        history.record(view())
        assertEquals(2, events.rows.size)
    }

    @Test
    fun `turning off and deleting removes only that profile's history`() = runTest {
        history.record(view(profileId = 1))
        history.record(view(profileId = 2))
        history.turnOff(1, keepHistory = false)
        assertEquals(listOf(2L), events.rows.map { it.profileId })
    }

    @Test
    fun `pruning drops rows from more than 400 days ago`() = runTest {
        history.prune(manila)
        assertEquals(today - 400, events.prunedBefore)
    }
}

private class FakeSettingDao : SettingDao {
    val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override suspend fun get(key: String): String? = values.value[key]
    override fun observe(key: String): Flow<String?> = values.map { it[key] }
    override suspend fun put(setting: SettingEntity) {
        values.value += setting.key to setting.value
    }
    override suspend fun remove(key: String) {
        values.value -= key
    }
    override suspend fun removeForProfile(profileId: Long, preserved: List<String>) = error("unused")
}

private class FakeWatchEventDao : WatchEventDao by stub() {
    val rows = mutableListOf<WatchEventEntity>()
    var prunedBefore: Long? = null

    override suspend fun insertAll(events: List<WatchEventEntity>) {
        rows += events
    }

    override suspend fun deleteForProfile(profileId: Long) {
        rows.removeAll { it.profileId == profileId }
    }

    override suspend fun deleteBefore(beforeDay: Long): Int {
        prunedBefore = beforeDay
        return rows.count { it.localDay < beforeDay }.also { rows.removeAll { it.localDay < beforeDay } }
    }
}
