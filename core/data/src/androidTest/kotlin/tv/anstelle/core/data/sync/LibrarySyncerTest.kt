package tv.anstelle.core.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ParentalRating
import org.jellyfin.sdk.model.serializer.toUUID
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.playback.MediaDurations
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelGroupEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ScheduleSlotEntity
import tv.anstelle.core.database.entity.ScheduleStateEntity
import tv.anstelle.core.jellyfin.JellyfinClients
import tv.anstelle.core.jellyfin.ServerProbe
import tv.anstelle.core.jellyfin.auth.JellyfinAuth
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.jellyfin.sync.FileDurationProbe
import tv.anstelle.core.jellyfin.sync.LibrarySource
import tv.anstelle.core.jellyfin.sync.LibrarySourceFactory
import tv.anstelle.core.jellyfin.sync.RemoteLibrary
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import tv.anstelle.core.model.SlotKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Library sync's deleting side against a real SQLite: nothing goes that the server still has. */
@RunWith(AndroidJUnit4::class)
class LibrarySyncerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AnstelleDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val time = TimeSource { NOW }
    private val settings = SettingsRepository(database.settingDao())
    private val tokens = object : TokenStore {
        val values = mutableMapOf(SERVER to "token")
        override fun get(jellyfinServerId: String) = values[jellyfinServerId]
        override fun put(jellyfinServerId: String, token: String) { values[jellyfinServerId] = token }
        override fun remove(jellyfinServerId: String) { values.remove(jellyfinServerId) }
    }

    // Only the reachability check goes over HTTP; it answers as this server.
    private val jellyfin = createJellyfin {
        this.context = this@LibrarySyncerTest.context
        clientInfo = ClientInfo("Sync test", "1")
        deviceInfo = DeviceInfo("test", "Test")
        apiClientFactory = OkHttpFactory(
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"Id":"$SERVER","ServerName":"Server","Version":"10.10.7"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build(),
        )
    }
    private val clients = JellyfinClients(jellyfin)

    /** What the server holds, and what one listing or fetch pass skips, as offset paging does while the library changes. */
    private val onServer = mutableSetOf(A, B, C)
    private var fetchSkips = emptySet<String>()
    private var listSkips = emptySet<String>()
    private var listingFails = false

    private val source = object : LibrarySource {
        override suspend fun serverTimeUtcMs() = NOW
        override suspend fun libraries() = listOf(RemoteLibrary(LIBRARY, "Movies", "movies"))
        override suspend fun parentalRatings() = emptyList<ParentalRating>()
        override suspend fun items(parentId: String, kinds: Set<BaseItemKind>, changedSinceUtcMs: Long?, startIndex: Int, limit: Int) =
            if (startIndex > 0) emptyList() else (onServer - fetchSkips).map(::movie)
        override suspend fun itemIds(parentId: String, kinds: Set<BaseItemKind>): List<String> {
            if (listingFails) throw ServerConnectException(ServerProblem.UNREACHABLE, "offline")
            return (onServer - listSkips).toList()
        }
        override suspend fun itemsByIds(parentId: String, kinds: Set<BaseItemKind>, ids: List<String>) = ids.filter { it in onServer }.map(::movie)
    }

    private val syncer = run {
        val items = database.mediaItemDao()
        val servers = database.mediaServerDao()
        val files = object : FileDurationProbe {
            override suspend fun durationMs(baseUrl: String, token: String, itemId: String, sourceId: String?): Long? = null
        }
        val profiles = ProfileRepository(
            database.profileDao(), items, database, servers, database.scheduleDao(), database.pairedClientDao(),
            settings, tokens, JellyfinAuth(clients), time, scope, Dispatchers.IO,
        )
        val sources = object : LibrarySourceFactory(clients) {
            override fun create(baseUrl: String, accessToken: String, userId: String, device: String?) = source
        }
        LibrarySyncer(
            database, servers, database.libraryDao(), items, database.scheduleDao(), tokens, ServerProbe(jellyfin, clients),
            sources, SyncStatusTracker(), profiles, database.backupDao(), time, MediaDurations(items, servers, tokens, settings, files, time),
        )
    }

    @After fun close() { scope.cancel(); database.close() }

    private suspend fun stored() = database.mediaItemDao().allIds(1).mapTo(HashSet()) { it.remoteId }

    @Test
    fun skippedItemsAreFetchedAndOnlyGoneOnesDeleted() = runTest {
        database.mediaServerDao().upsert(MediaServerEntity(1, SERVER, "Server", "http://jellyfin.test", USER, "User", null, 0))

        // The first fetch skips C and the listing skips B: both are still on the server.
        fetchSkips = setOf(C)
        listSkips = setOf(B)
        assertTrue(syncer.sync(1))
        assertEquals(setOf(A, B, C), stored())

        // B airs on a channel, then leaves the server.
        val b = database.mediaItemDao().allIds(1).single { it.remoteId == B }.id
        database.channelDao().insertGroup(ChannelGroupEntity(1, "Mine", 0, 1))
        database.channelDao().insert(
            ChannelEntity(1, 1, "Movies", null, ChannelKind.GENERATED, 1, PlaybackOrder.EPISODE_ORDER, 1, PlaybackOrder.SHUFFLE, 1, true, false, false, 1, null, 0, 0),
        )
        database.scheduleDao().replaceFrom(
            1, NOW, listOf(ScheduleSlotEntity(channelId = 1, kind = SlotKind.PROGRAM, mediaItemId = b, startUtcMs = NOW, endUtcMs = NOW + HOUR_MS)),
            ScheduleStateEntity(1, NOW + HOUR_MS, ByteArray(0), inputsHash = 42, updatedAtUtcMs = 0),
        )
        onServer -= B
        fetchSkips = emptySet()
        listSkips = emptySet()
        assertTrue(syncer.sync(1, SyncOptions(forceReconcile = true)))
        assertEquals(setOf(A, C), stored())
        assertEquals(0L, database.scheduleDao().state(1)?.inputsHash)
    }

    @Test
    fun aListingThatFailsDeletesNothing() = runTest {
        database.mediaServerDao().upsert(MediaServerEntity(1, SERVER, "Server", "http://jellyfin.test", USER, "User", null, 0))
        assertTrue(syncer.sync(1))
        listingFails = true
        assertFalse(syncer.sync(1, SyncOptions(forceReconcile = true)))
        assertEquals(setOf(A, B, C), stored())
    }

    private fun movie(id: String) = BaseItemDto(id = id.toUUID(), type = BaseItemKind.MOVIE, name = "Movie $id", runTimeTicks = HOUR_MS * 10_000)

    private companion object {
        const val NOW = 1_000_000_000L
        const val HOUR_MS = 3_600_000L
        const val SERVER = "5e000000000000000000000000000001"
        const val USER = "05e0000000000000000000000000000a"
        const val LIBRARY = "11b0000000000000000000000000000c"
        const val A = "a0000000000000000000000000000001"
        const val B = "b0000000000000000000000000000002"
        const val C = "c0000000000000000000000000000003"
    }
}
