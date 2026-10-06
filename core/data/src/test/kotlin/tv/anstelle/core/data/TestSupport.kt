package tv.anstelle.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tv.anstelle.core.database.dao.CatalogRow
import tv.anstelle.core.database.dao.GuideAiring
import tv.anstelle.core.database.dao.ScheduleDao
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.database.entity.ScheduleStateEntity
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.SlotKind
import java.lang.reflect.Proxy

/**
 * An implementation of [T] whose every method fails. Fakes delegate to it (`by stub()`) and
 * override only what a test uses.
 */
inline fun <reified T : Any> stub(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${T::class.simpleName}.${method.name} is not used by this test")
    } as T

/** A catalog row with sensible defaults; lists are written as plain lists. */
fun item(
    id: Long,
    type: MediaType = MediaType.EPISODE,
    title: String = "Item $id",
    series: String? = null,
    show: String? = series?.let { "Show $it" },
    season: Int? = null,
    episode: Int? = null,
    minutes: Int = 22,
    genres: List<String> = emptyList(),
    studios: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    collections: List<String> = emptyList(),
    year: Int? = null,
    rating: String? = null,
    score: Int? = null,
    library: String? = "library-1",
    remoteId: String = "item-$id",
) = CatalogRow(
    id = id,
    remoteId = remoteId,
    libraryRemoteId = library,
    type = type,
    title = title,
    seriesRemoteId = series,
    showTitle = show,
    season = season,
    episode = episode,
    durationMs = minutes * 60_000L,
    genres = pipes(genres),
    studios = pipes(studios),
    tags = pipes(tags),
    collections = pipes(collections),
    year = year,
    officialRating = rating,
    ratingScore = score,
)

/** [count] episodes of series [key] in season 1, ids `idBase + 1 ..`. */
fun episodes(
    key: String,
    idBase: Long,
    count: Int,
    genres: List<String> = emptyList(),
    year: Int? = null,
    score: Int? = null,
    minutes: Int = 22,
) = List(count) { e ->
    item(
        id = idBase + e + 1,
        series = key,
        season = 1,
        episode = e + 1,
        genres = genres,
        year = year,
        score = score,
        minutes = minutes,
    )
}

/** The metadata row of series [key]. */
fun seriesRow(id: Long, key: String, title: String) = item(id = id, type = MediaType.SERIES, title = title, remoteId = key)

private fun pipes(values: List<String>) = if (values.isEmpty()) "" else values.joinToString("|", "|", "|")

/** Schedule slots and states in memory, with the same rules as the SQL queries. */
class FakeScheduleDao : ScheduleDao {
    val slots = sortedMapOf<Pair<Long, Long>, ScheduleSlotEntity>(compareBy({ it.first }, { it.second }))
    val states = HashMap<Long, ScheduleStateEntity>()

    fun slotsOf(channelId: Long) = slots.values.filter { it.channelId == channelId }

    override suspend fun slotAt(channelId: Long, nowUtcMs: Long): ScheduleSlotEntity? =
        slots.values.lastOrNull { it.channelId == channelId && it.startUtcMs <= nowUtcMs }

    override suspend fun slotsFrom(channelId: Long, afterUtcMs: Long, limit: Int): List<ScheduleSlotEntity> =
        slots.values.filter { it.channelId == channelId && it.startUtcMs >= afterUtcMs }.take(limit)

    override suspend fun programItemIdsFrom(channelId: Long, fromUtcMs: Long): List<Long> =
        slots.values.filter { it.channelId == channelId && it.startUtcMs >= fromUtcMs && it.kind == SlotKind.PROGRAM }
            .mapNotNull { it.mediaItemId }

    /** Items are titled "Item <id>", as [item] names them. */
    override suspend fun guideWindow(channelIds: List<Long>, fromUtcMs: Long, toUtcMs: Long, maxSlotMs: Long): List<GuideAiring> =
        slots.values
            .filter { it.channelId in channelIds && it.startUtcMs > fromUtcMs - maxSlotMs && it.startUtcMs < toUtcMs && it.endUtcMs > fromUtcMs }
            .map { GuideAiring(it.channelId, it.kind, it.mediaItemId, it.startUtcMs, it.endUtcMs, it.mediaItemId?.let { id -> "Item $id" }, null, null, null, null) }

    override suspend fun maxSlotMs(): Long = slots.values.maxOfOrNull { it.endUtcMs - it.startUtcMs } ?: 0

    override suspend fun insertSlots(slots: List<ScheduleSlotEntity>) {
        for (slot in slots) this.slots[slot.channelId to slot.startUtcMs] = slot
    }

    override suspend fun deleteFrom(channelId: Long, fromUtcMs: Long) {
        slots.keys.removeIf { it.first == channelId && it.second >= fromUtcMs }
    }

    override suspend fun deleteEndedBefore(beforeUtcMs: Long): Int = error("unused")

    override suspend fun state(channelId: Long): ScheduleStateEntity? = states[channelId]

    override suspend fun upsertState(state: ScheduleStateEntity) {
        states[state.channelId] = state
    }

    override suspend fun invalidateForItems(mediaItemIds: List<Long>, nowUtcMs: Long): Int = error("unused")

    override fun observeGeneratedUntil(): Flow<Long?> = flowOf(null)
}
