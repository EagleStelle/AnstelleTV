package tv.anstelle.core.database

import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.database.entity.WatchEventEntity
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.WatchSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each dashboard query against a real SQLite, and their cost over 400 days of heavy watching. */
@RunWith(AndroidJUnit4::class)
class WatchEventDaoTest {
    private lateinit var db: AnstelleDatabase
    private val dao get() = db.watchEventDao()
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val today = 20_000L

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(FILE)
        // A file, not memory, so the timings read like the app's.
        db = Room.databaseBuilder(context, AnstelleDatabase::class.java, FILE).build()
    }

    @After
    fun close() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(FILE)
    }

    private suspend fun profile(id: Long) = db.profileDao().upsert(ProfileEntity(id, "P$id", ProfileRole.MEMBER, null, null, null, null, 0, 0))

    private suspend fun server() = db.mediaServerDao().upsert(MediaServerEntity(1, "jf-1", "Home", "http://jf", "u", "u", "12.1.0", 0))

    private suspend fun item(id: Long, type: MediaType, title: String, show: String? = null, series: String? = null, genres: String = "", backdrop: String? = null, logo: String? = null) =
        db.mediaItemDao().upsertAll(
            listOf(
                MediaItemEntity(
                    id, 1, null, "remote-$id", type, title, null, series, show, null, null, 22 * minute, genres, "", "", null, null, null, null, null, null,
                    HdrType.SDR, null, null, null, null, null, null, null, null, null, MediaTracks(), "", null, backdrop, logo, 0,
                ),
            ),
        )

    private suspend fun channel(id: Long, number: Int, name: String) = db.channelDao().insert(
        ChannelEntity(id, number, name, null, ChannelKind.GENERATED, 1, PlaybackOrder.SHUFFLE, 1, false, false, null, null, false, 0, 0),
    )

    private fun event(
        profileId: Long = 1,
        localDay: Long = today,
        startUtcMs: Long = localDay * day + 20 * 60 * minute,
        minutes: Long = 22,
        source: WatchSource = WatchSource.CHANNEL,
        channelId: Long? = 1,
        itemId: Long = 10,
        title: String = "Pilot",
        show: String? = "Show A",
        type: MediaType = MediaType.EPISODE,
        completed: Boolean = false,
    ) = WatchEventEntity(0, profileId, source, channelId, null, itemId, title, show, type, startUtcMs, minutes * minute, localDay, completed)

    @Test
    fun viewsAreOneProfilesDaysInStartOrder() = runTest {
        profile(1); profile(2)
        dao.insertAll(
            listOf(
                event(startUtcMs = today * day + 3 * minute),
                event(startUtcMs = today * day + 1 * minute),
                event(localDay = today - 1),
                event(localDay = today + 1),
                event(profileId = 2),
            ),
        )
        val views = dao.views(1, today - 1, today)
        assertEquals(listOf(today - 1, today, today), views.map { it.localDay })
        assertEquals(today * day + minute, views[1].startUtcMs)
        assertEquals(listOf(today - 1, today, today + 1), dao.watchedDays(1))
        assertEquals(listOf(today - 1 to 22 * minute, today to 44 * minute), dao.dayTotals(1, today - 1, today).map { it.localDay to it.playedMs })
    }

    @Test
    fun anHourClipsWhatPlayedAcrossIt() = runTest {
        profile(1)
        val start = today * day + 20 * 60 * minute + 40 * minute // 20:40
        dao.insertAll(listOf(event(startUtcMs = start, minutes = 30)))
        val hour21 = today * day + 21 * 60 * minute
        assertEquals(30 * minute, dao.playedTotal(1, today, today, 0, OPEN))
        assertEquals(10 * minute, dao.playedTotal(1, today, today, hour21, hour21 + 60 * minute))
        assertEquals(0, dao.playedTotal(1, today, today, hour21 + 60 * minute, hour21 + 120 * minute))
    }

    @Test
    fun topListsGroupAndShowTheLastItemWatched() = runTest {
        profile(1); server()
        channel(1, 1042, "Fright Night")
        channel(2, 3001, "Case Files")
        item(10, MediaType.EPISODE, "Pilot", "Show A", "series-a", "|Horror|")
        item(11, MediaType.EPISODE, "Second", "Show A", "series-a", "|Horror|")
        item(20, MediaType.MOVIE, "Film", genres = "|Crime|Drama|")
        dao.insertAll(
            listOf(
                event(channelId = 1, itemId = 10, minutes = 22, startUtcMs = today * day + 1_000),
                event(channelId = 1, itemId = 11, title = "Second", minutes = 22, startUtcMs = today * day + 2_000),
                event(channelId = 2, itemId = 20, title = "Film", show = null, type = MediaType.MOVIE, minutes = 90),
                // A channel since deleted counts in the time, not the list.
                event(channelId = 99, itemId = 10, minutes = 5),
                event(channelId = null, source = WatchSource.ON_DEMAND, itemId = 20, title = "Film", show = null, type = MediaType.MOVIE, minutes = 10),
            ),
        )
        val channels = dao.topChannels(1, today, today, 0, OPEN, 5)
        assertEquals(listOf("Case Files" to 90 * minute, "Fright Night" to 44 * minute), channels.map { it.title to it.playedMs })
        assertEquals(listOf(3001, 1042), channels.map { it.number })
        assertEquals(11L, channels[1].itemId, "the item watched last")

        val series = dao.topSeries(1, today, today, 0, OPEN, 5, MediaType.EPISODE)
        assertEquals(listOf("Show A" to 49 * minute), series.map { it.title to it.playedMs })
        val movies = dao.topMovies(1, today, today, 0, OPEN, 5, MediaType.MOVIE)
        assertEquals(listOf("Film" to 100 * minute), movies.map { it.title to it.playedMs })
        assertEquals(1, dao.topChannels(1, today, today, 0, OPEN, 1).size)

        val genres = dao.genreRows(1, today, today, 0, OPEN).associate { it.itemId to (it.genres to it.playedMs) }
        assertEquals("|Crime|Drama|" to 100 * minute, genres[20])
        assertEquals("|Horror|" to 27 * minute, genres[10])
    }

    @Test
    fun genresSkipAnItemWhoseIdWasTakenAgain() = runTest {
        profile(1); server()
        item(10, MediaType.EPISODE, "Someone else", "Show B", "series-b", "|Comedy|")
        dao.insertAll(listOf(event(itemId = 10, title = "Pilot")))
        assertTrue(dao.genreRows(1, today, today, 0, OPEN).isEmpty())
    }

    @Test
    fun artworkFallsBackToTheSeries() = runTest {
        server()
        item(1, MediaType.SERIES, "Show A", backdrop = "series-backdrop", logo = "series-logo")
        item(10, MediaType.EPISODE, "Pilot", "Show A", series = "remote-1")
        val art = dao.artwork(listOf(10)).single()
        assertEquals("http://jf", art.baseUrl)
        assertEquals("remote-10", art.remoteId)
        assertEquals(null, art.backdropTag)
        assertEquals("series-backdrop", art.seriesBackdropTag)
        assertEquals("series-logo", art.seriesLogoTag)
    }

    @Test
    fun deletesAreByProfileAndAge() = runTest {
        profile(1); profile(2)
        dao.insertAll(listOf(event(profileId = 1), event(profileId = 2), event(profileId = 2, localDay = today - 401), event(profileId = 2, localDay = today - 400)))
        dao.deleteForProfile(1)
        assertEquals(emptyList(), dao.watchedDays(1))
        assertEquals(1, dao.deleteBefore(today - 400))
        assertEquals(listOf(today - 400, today), dao.watchedDays(2))
    }

    @Test
    fun fourHundredDaysOfHeavyWatchingReadQuickly() = runTest {
        profile(1); server()
        for (c in 1L..20) channel(c, 1000 + c.toInt(), "Channel $c")
        for (i in 1L..200) item(i, if (i % 4 == 0L) MediaType.MOVIE else MediaType.EPISODE, "Item $i", if (i % 4 == 0L) null else "Show ${i % 30}", genres = "|Genre${i % 12}|Genre${i % 7}|")
        val rows = ArrayList<WatchEventEntity>()
        for (d in 0 until 400) for (v in 0 until 50) {
            val itemId = 1L + (d * 50 + v) % 200
            val movie = itemId % 4 == 0L
            rows += event(
                localDay = today - d,
                startUtcMs = (today - d) * day + v * 25 * minute,
                minutes = 22,
                channelId = if (v % 3 == 0) null else 1 + v % 20L,
                source = if (v % 3 == 0) WatchSource.ON_DEMAND else WatchSource.CHANNEL,
                itemId = itemId,
                title = "Item $itemId",
                show = if (movie) null else "Show ${itemId % 30}",
                type = if (movie) MediaType.MOVIE else MediaType.EPISODE,
                completed = v % 2 == 0,
            )
        }
        dao.insertAll(rows)

        // What opening on the week reads, warm, as the dashboard opens it.
        suspend fun open(fromDay: Long, toDay: Long): Long {
            val started = SystemClock.elapsedRealtimeNanos()
            dao.watchedDays(1)
            dao.views(1, fromDay, toDay)
            dao.dayTotals(1, today - 7, today)
            dao.playedTotal(1, fromDay, toDay, 0, OPEN)
            val ids = dao.topChannels(1, fromDay, toDay, 0, OPEN, 5).mapNotNull { it.itemId } +
                dao.topSeries(1, fromDay, toDay, 0, OPEN, 5, MediaType.EPISODE).mapNotNull { it.itemId } +
                dao.topMovies(1, fromDay, toDay, 0, OPEN, 5, MediaType.MOVIE).mapNotNull { it.itemId } +
                dao.genreRows(1, fromDay, toDay, 0, OPEN).take(5).map { it.itemId }
            dao.artwork(ids.distinct())
            return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        }
        open(today - 6, today)
        val week = open(today - 6, today)
        val year = open(today - 364, today)
        android.util.Log.i("Stats", "Seeded 20000 rows: week reads in $week ms, year in $year ms")
        assertTrue(week < 50, "week took $week ms")
    }

    private companion object {
        const val FILE = "watch-event-test.db"
        const val OPEN = Long.MAX_VALUE / 4
    }
}
