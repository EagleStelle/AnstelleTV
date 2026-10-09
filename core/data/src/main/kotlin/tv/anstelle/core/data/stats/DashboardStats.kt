package tv.anstelle.core.data.stats

import android.os.Build
import android.os.Trace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AnstelleDispatchers
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.Dispatcher
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Artwork
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.dao.DayTotal
import tv.anstelle.core.database.dao.GenreRow
import tv.anstelle.core.database.dao.TopRow
import tv.anstelle.core.database.dao.ViewTime
import tv.anstelle.core.database.dao.WatchEventDao
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.WatchSource
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** The habits a dashboard period shows. Streaks run over the whole history. */
data class Habits(
    val totalMs: Long,
    val liveMs: Long,
    val onDemandMs: Long,
    /** Null on a Day chart, or before the first day anything was watched. */
    val dailyAverageMs: Long?,
    /** The local hour (0–23) with the most watching. */
    val peakHour: Int?,
    val streaks: Streaks,
    val longestSittingMs: Long,
    /** Rows kept: a view that ran past midnight counts on both days. */
    val views: Int,
    val episodesFinished: Int,
    val moviesFinished: Int,
)

/** A dashboard period's chart, with today against the week before it, and its habits. */
class ScreenTime(
    val window: StatsWindow,
    /** Live TV time per bar. */
    val live: LongArray,
    /** On demand time per bar. */
    val onDemand: LongArray,
    val todayMs: Long,
    /** The daily average of the 7 days before today, counted from the first day anything was watched; null before then. */
    val weekAverageMs: Long?,
    val habits: Habits,
    /** The first day anything is kept for, or null when nothing is. */
    val firstDay: Long?,
)

/** A list entry: its name, the time watched, and the artwork of the item watched last (or most, for a genre). */
data class TopEntry(val title: String, val playedMs: Long, val artwork: Artwork, val number: Int? = null)

data class TopLists(
    /** All the time watched in the span the lists cover, for each entry's share. */
    val totalMs: Long,
    val channels: List<TopEntry>,
    val series: List<TopEntry>,
    val movies: List<TopEntry>,
    val genres: List<TopEntry>,
)

/**
 * The dashboard's figures, read only while it is open: grouped SQL over one profile's indexed
 * `localDay` range, and plain Kotlin over the results. Both reads run in the "Stats" trace
 * section and log how long they took.
 */
@Singleton
class DashboardStats @Inject constructor(
    private val dao: WatchEventDao,
    private val database: AnstelleDatabase,
    private val time: TimeSource,
    @Dispatcher(AnstelleDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** Emits at once and then whenever the history changes, which is when a view stops. */
    fun changes(): Flow<Unit> = database.invalidationTracker.createFlow("watch_event").map { }

    fun today(zone: ZoneId = ZoneId.systemDefault()): Long = LocalDays.of(time.nowUtcMs(), zone)

    suspend fun screenTime(profileId: Long, window: StatsWindow, zone: ZoneId = ZoneId.systemDefault()): ScreenTime = traced("screen time") {
        val today = today(zone)
        val days = dao.watchedDays(profileId)
        val views = dao.views(profileId, window.firstDay, window.lastDay)
        val recent = dao.dayTotals(profileId, today - WEEK_DAYS, today)
        buildScreenTime(window, views, days, recent, today, zone)
    }

    suspend fun topLists(profileId: Long, span: StatsSpan, limit: Int = LIST_SIZE): TopLists = traced("lists") {
        val (fromDay, toDay, fromUtc, toUtc) = span
        val total = dao.playedTotal(profileId, fromDay, toDay, fromUtc, toUtc)
        val channels = dao.topChannels(profileId, fromDay, toDay, fromUtc, toUtc, limit)
        val series = dao.topSeries(profileId, fromDay, toDay, fromUtc, toUtc, limit, MediaType.EPISODE)
        val movies = dao.topMovies(profileId, fromDay, toDay, fromUtc, toUtc, limit, MediaType.MOVIE)
        val genres = topGenres(dao.genreRows(profileId, fromDay, toDay, fromUtc, toUtc), limit)
        val ids = (channels + series + movies).mapNotNullTo(HashSet()) { it.itemId } + genres.map { it.itemId }
        val art = if (ids.isEmpty()) emptyMap() else dao.artwork(ids.toList()).associate {
            it.id to Artwork.of(it.baseUrl, it.remoteId, it.backdropTag, it.logoTag, it.seriesRemoteId, it.seriesBackdropTag, it.seriesLogoTag)
        }
        fun List<TopRow>.entries() = map { TopEntry(it.title, it.playedMs, it.itemId?.let(art::get) ?: Artwork.None, it.number) }
        TopLists(
            totalMs = total,
            channels = channels.entries(),
            series = series.entries(),
            movies = movies.entries(),
            genres = genres.map { TopEntry(it.name, it.playedMs, art[it.itemId] ?: Artwork.None) },
        )
    }

    private suspend fun <T> traced(what: String, block: suspend () -> T): T = withContext(io) {
        val cookie = traceCookies.incrementAndGet()
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(SECTION, cookie)
        val started = System.nanoTime()
        try {
            block()
        } finally {
            if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(SECTION, cookie)
            AppLog.d(TAG, "Dashboard $what in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    companion object {
        /** The trace section macrobenchmarks measure the dashboard by. */
        const val SECTION = "Stats"
        private const val TAG = "Stats"
        const val LIST_SIZE = 5
        private const val WEEK_DAYS = 7L

        private val traceCookies = AtomicInteger()
    }
}

/** A genre's time, and the item that gave it the most, for its artwork. */
internal data class GenreTotal(val name: String, val playedMs: Long, val itemId: Long)

/** Every genre of the items watched, most watched first: an item's time counts for each of its genres. */
internal fun topGenres(rows: List<GenreRow>, limit: Int): List<GenreTotal> {
    val totals = HashMap<String, Long>()
    val topItem = HashMap<String, GenreRow>()
    for (row in rows) {
        for (genre in row.genres.split('|')) {
            if (genre.isEmpty()) continue
            totals[genre] = (totals[genre] ?: 0L) + row.playedMs
            val best = topItem[genre]
            if (best == null || row.playedMs > best.playedMs) topItem[genre] = row
        }
    }
    return totals.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { (name, played) -> GenreTotal(name, played, topItem.getValue(name).itemId) }
}

/**
 * [window]'s chart and habits from its [views] (sorted by start), every day anything was
 * watched ([days], ascending) and the totals of the last week's days ([recent]).
 */
internal fun buildScreenTime(
    window: StatsWindow,
    views: List<ViewTime>,
    days: List<Long>,
    recent: List<DayTotal>,
    today: Long,
    zone: ZoneId,
): ScreenTime {
    val bars = window.bars
    val live = LongArray(bars)
    val onDemand = LongArray(bars)
    val hours = LongArray(24)
    val liveHours = if (window.period == StatsPeriod.DAY) LongArray(24) else null
    val starts = LongArray(views.size)
    val durations = LongArray(views.size)
    var liveMs = 0L
    var episodes = 0
    var movies = 0
    views.forEachIndexed { i, view ->
        starts[i] = view.startUtcMs
        durations[i] = view.durationMs
        val isLive = view.source != WatchSource.ON_DEMAND
        if (isLive) liveMs += view.durationMs
        if (view.completed && view.mediaType == MediaType.EPISODE) episodes++
        if (view.completed && view.mediaType == MediaType.MOVIE) movies++
        StatsMath.addHours(view.startUtcMs, view.durationMs, zone, hours)
        if (liveHours != null) {
            if (isLive) StatsMath.addHours(view.startUtcMs, view.durationMs, zone, liveHours)
        } else {
            val bar = window.barOf(view.localDay)
            when {
                bar < 0 -> Unit
                isLive -> live[bar] += view.durationMs
                else -> onDemand[bar] += view.durationMs
            }
        }
    }
    if (liveHours != null) {
        for (h in 0 until 24) {
            live[h] = liveHours[h]
            onDemand[h] = hours[h] - liveHours[h]
        }
    }
    val totalMs = durations.sum()
    val firstDay = days.firstOrNull()

    val averageFrom = maxOf(today - 7, firstDay ?: today)
    val weekAverage = if (averageFrom > today - 1) null else recent.filter { it.localDay in averageFrom until today }.sumOf { it.playedMs } / (today - averageFrom)

    val countedFrom = maxOf(window.firstDay, firstDay ?: Long.MAX_VALUE)
    val countedTo = minOf(window.lastDay, today)
    val dailyAverage = if (window.period == StatsPeriod.DAY || countedFrom > countedTo) null else totalMs / (countedTo - countedFrom + 1)

    return ScreenTime(
        window = window,
        live = live,
        onDemand = onDemand,
        todayMs = recent.firstOrNull { it.localDay == today }?.playedMs ?: 0L,
        weekAverageMs = weekAverage,
        habits = Habits(
            totalMs = totalMs,
            liveMs = liveMs,
            onDemandMs = totalMs - liveMs,
            dailyAverageMs = dailyAverage,
            peakHour = StatsMath.peakHour(hours),
            streaks = StatsMath.streaks(days, today),
            longestSittingMs = StatsMath.longestSitting(starts, durations),
            views = views.size,
            episodesFinished = episodes,
            moviesFinished = movies,
        ),
        firstDay = firstDay,
    )
}
