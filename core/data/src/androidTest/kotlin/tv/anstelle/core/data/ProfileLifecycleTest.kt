package tv.anstelle.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ProfileAccountEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.database.entity.WatchEventEntity
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.jellyfin.JellyfinClients
import tv.anstelle.core.jellyfin.ServerProbe
import tv.anstelle.core.jellyfin.sync.LibrarySourceFactory
import tv.anstelle.core.jellyfin.sync.FileDurationProbe
import tv.anstelle.core.jellyfin.auth.JellyfinAuth
import tv.anstelle.core.jellyfin.auth.AuthSession
import tv.anstelle.core.jellyfin.auth.TokenStore
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.WatchSource
import tv.anstelle.core.data.channels.ChannelLogos
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelWords
import tv.anstelle.core.data.channels.ChannelWordsSource
import tv.anstelle.core.data.channels.ScheduleUpdater
import tv.anstelle.core.data.playback.MediaDurations
import tv.anstelle.core.data.sync.LibrarySyncer
import tv.anstelle.core.data.sync.SyncStatusTracker
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Real SQLite verifies that permissions and cleanup agree, including numeric setting-key collisions. */
@RunWith(AndroidJUnit4::class)
class ProfileLifecycleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AnstelleDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = SettingsRepository(database.settingDao())
    private val tokens = object : TokenStore {
        val values = mutableMapOf<String, String>()
        override fun get(jellyfinServerId: String) = values[jellyfinServerId]
        override fun put(jellyfinServerId: String, token: String) { values[jellyfinServerId] = token }
        override fun remove(jellyfinServerId: String) { values.remove(jellyfinServerId) }
    }
    private val jellyfin = createJellyfin {
        this.context = this@ProfileLifecycleTest.context
        clientInfo = ClientInfo("Lifecycle test", "1")
        deviceInfo = DeviceInfo("test", "Test")
        apiClientFactory = OkHttpFactory(OkHttpClient())
    }
    private val clients = JellyfinClients(jellyfin)
    private val auth = JellyfinAuth(clients)
    private val profiles = ProfileRepository(
        database.profileDao(), database.mediaItemDao(), database, database.mediaServerDao(), database.scheduleDao(),
        database.pairedClientDao(), settings, tokens, auth, TimeSource { 1000L }, scope, Dispatchers.IO,
    )

    private fun removal(): ServerRemovalService {
        val time = TimeSource { 1000L }
        val items = database.mediaItemDao()
        val servers = database.mediaServerDao()
        val schedules = database.scheduleDao()
        val channelDao = database.channelDao()
        val words = ChannelWordsSource { ChannelWords(emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList()) }
        val files = object : FileDurationProbe {
            override suspend fun durationMs(baseUrl: String, token: String, itemId: String, sourceId: String?): Long? = null
        }
        val durations = MediaDurations(items, servers, tokens, settings, files, time)
        val updater = ScheduleUpdater(channelDao, schedules, items, ScheduleRepository(schedules, time), words, time, durations)
        val logos = ChannelLogos(context)
        val channels = ChannelRepository(database, channelDao, schedules, items, database.libraryDao(), settings, words, logos, updater, time, scope, Dispatchers.IO)
        val syncer = LibrarySyncer(database, servers, database.libraryDao(), items, schedules, tokens,
            ServerProbe(jellyfin, clients), LibrarySourceFactory(clients), SyncStatusTracker(), profiles, database.backupDao(), time, durations)
        val web = object : WebServerController {
            override val status = MutableStateFlow(WebServerStatus.Stopped)
            override fun startIfEnabled() = Unit
            override fun stop() = Unit
            override suspend fun unpair(clientId: Long) = Unit
        }
        return ServerRemovalService(context, database, settings, tokens, auth, syncer, channels, updater, logos, web, scope, Dispatchers.IO)
    }

    @After fun close() { scope.cancel(); database.close() }

    private suspend fun seed(role: ProfileRole = ProfileRole.MEMBER, ownsServer: Boolean = false) {
        database.profileDao().upsert(ProfileEntity(1, "Owner", ProfileRole.OWNER, null, null, null, null, 0, 0))
        database.profileDao().upsert(ProfileEntity(2, "Viewer", role, null, null, null, null, 1, 0))
        database.mediaServerDao().upsert(MediaServerEntity(2, "server", "Server", "http://127.0.0.1:1", "u1", "User", null, 0, ownerProfileId = if (ownsServer) 2 else 1))
        database.profileDao().upsertAccount(ProfileAccountEntity(2, 2, "u2", "Viewer", "viewer"))
        settings.set(Settings.ActiveProfileId, 2)
        settings.set(Settings.dashboard(2), false)
        settings.set(Settings.ratingTable(2), "0\tG")
        settings.set(Settings.fileDurations(2), "saved runtimes")
        tokens.put("server", "server-token")
        tokens.put(ProfileRepository.tokenKey("server", "viewer"), "profile-token")
        database.pairedClientDao().insert(PairedClientEntity(profileId = 2, tokenHash = "paired", name = "Browser", userAgent = null, createdAtUtcMs = 0, lastSeenUtcMs = 0, lastAddress = null))
        database.watchEventDao().insertAll(listOf(WatchEventEntity(profileId = 2, source = WatchSource.ON_DEMAND,
            channelId = null, liveChannelId = null, mediaItemId = null, title = "Movie", showTitle = null, mediaType = MediaType.MOVIE,
            startUtcMs = 0, durationMs = 1, localDay = 0, completed = true)))
    }

    private suspend fun assertRemoved() {
        assertNull(database.profileDao().byId(2))
        assertTrue(database.profileDao().accounts(2).isEmpty())
        assertTrue(database.watchEventDao().watchedDays(2).isEmpty())
        assertTrue(database.pairedClientDao().observeAll().first().isEmpty())
        assertNull(database.settingDao().get(Settings.dashboard(2).name))
        assertEquals("0\tG", settings.get(Settings.ratingTable(2)))
        assertEquals("saved runtimes", settings.get(Settings.fileDurations(2)))
        assertEquals(1L, settings.get(Settings.ActiveProfileId))
        assertEquals("server-token", tokens.get("server"))
        assertNull(tokens.get(ProfileRepository.tokenKey("server", "viewer")))
        assertNotNull(database.mediaServerDao().byId(2))
    }

    @Test fun memberSignOutRemovesOnlyPersonalData() = runTest {
        seed()
        assertTrue(profiles.signOut(2))
        assertRemoved()
    }

    @Test fun disconnectedAdminCanSignOut() = runTest {
        seed(ProfileRole.ADMIN)
        assertTrue(profiles.signOut(2))
        assertRemoved()
    }

    @Test fun serverOwningAdminCannotSignOutOrBeDeletedByOwner() = runTest {
        seed(ProfileRole.ADMIN, ownsServer = true)
        assertFalse(profiles.signOut(2))
        settings.set(Settings.ActiveProfileId, 1)
        assertFalse(profiles.delete(2))
        assertFalse(profiles.signOut(1))
        assertNotNull(database.profileDao().byId(2))
        assertEquals("profile-token", tokens.get(ProfileRepository.tokenKey("server", "viewer")))
        profiles.setAdmin(2, false)
        assertEquals(ProfileRole.ADMIN, database.profileDao().byId(2)?.role)
        tokens.remove("server")
        tokens.remove(ProfileRepository.tokenKey("server", "viewer"))
        settings.set(Settings.ActiveProfileId, 2)
        assertFalse(profiles.signOut(2))
    }

    @Test fun ownerCanDeleteDisconnectedAdminUsingTheSameCleanup() = runTest {
        seed(ProfileRole.ADMIN)
        settings.set(Settings.ActiveProfileId, 1)
        assertTrue(profiles.delete(2))
        assertRemoved()
    }

    @Test fun connectionSignInCreatesAnAdminForThatUserRatherThanTheAddingProfile() = runTest {
        seed()
        settings.set(Settings.ActiveProfileId, 1)
        val server = requireNotNull(database.mediaServerDao().byId(2))
        database.mediaServerDao().upsert(server.copy(userId = "connection-user", ownerProfileId = null))
        val id = profiles.connectServerProfile(2, AuthSession("connection-profile-token", "connection-user", "Connection user", null), "connection")
        assertEquals(id, database.mediaServerDao().byId(2)?.ownerProfileId)
        assertEquals(ProfileRole.ADMIN, database.profileDao().byId(id)?.role)
        assertEquals("Connection user", database.profileDao().byId(id)?.name)
        assertEquals(1L, settings.get(Settings.ActiveProfileId))
        assertEquals("connection-user", database.profileDao().account(id, 2)?.userId)
        assertFalse(profiles.delete(id))
        settings.set(Settings.ActiveProfileId, id)
        assertFalse(profiles.signOut(id))
    }

    @Test fun firstConnectionCreatesTheOwnerAndMakesItActive() = runTest {
        database.mediaServerDao().upsert(MediaServerEntity(1, "first-server", "First server", "http://127.0.0.1:1", "first-user", "First user", null, 0))
        val id = profiles.connectServerProfile(1, AuthSession("first-token", "first-user", "First user", null), "first")
        assertEquals(ProfileRole.OWNER, database.profileDao().byId(id)?.role)
        assertEquals(id, database.mediaServerDao().byId(1)?.ownerProfileId)
        assertEquals(id, settings.get(Settings.ActiveProfileId))
        assertFalse(profiles.signOut(id))
    }

    private suspend fun seedRemoval() {
        seed(ProfileRole.ADMIN, ownsServer = true)
        database.mediaServerDao().upsert(MediaServerEntity(1, "owner-server", "Owner server", "http://127.0.0.1:1", "owner", "Owner", null, 0, ownerProfileId = 1))
        database.profileDao().upsertAccount(ProfileAccountEntity(1, 1, "owner", "Owner", "owner"))
        tokens.put("owner-server", "owner-token")
        database.profileDao().upsert(ProfileEntity(3, "Linked member", ProfileRole.MEMBER, null, null, null, null, 2, 0))
        for (server in listOf(1L, 2L)) {
            database.profileDao().upsertAccount(ProfileAccountEntity(3, server, "member", "Member", "member-$server"))
            val remoteId = if (server == 1L) "owner-server" else "server"
            tokens.put(ProfileRepository.tokenKey(remoteId, "member-$server"), "member-token")
        }
        database.libraryDao().upsert(LibraryEntity(serverId = 2, remoteId = "library", name = "Library", collectionType = "movies", isEnabled = true, lastSyncUtcMs = null))
    }

    private suspend fun assertServerAndProfilesRemoved(actorId: Long) {
        seedRemoval()
        val service = removal()
        val plan = service.observe(2).first()
        assertTrue(plan.canRemove(actorId))
        assertTrue(plan.explanation.contains("Viewer, Linked member"))
        service.remove(2, null, actorId)
        assertNull(database.mediaServerDao().byId(2))
        assertTrue(database.libraryDao().forServer(2).isEmpty())
        for (id in listOf(2L, 3L)) {
            assertNull(database.profileDao().byId(id))
            assertTrue(database.profileDao().accounts(id).isEmpty())
            assertTrue(database.watchEventDao().watchedDays(id).isEmpty())
        }
        assertTrue(database.pairedClientDao().observeAll().first().isEmpty())
        assertNull(database.settingDao().get(Settings.dashboard(2).name))
        assertNull(database.settingDao().get(Settings.ratingTable(2).name))
        assertNull(tokens.get("server"))
        assertNull(tokens.get(ProfileRepository.tokenKey("server", "viewer")))
        assertNull(tokens.get(ProfileRepository.tokenKey("owner-server", "member-1")))
        assertNotNull(database.profileDao().byId(1))
        assertNotNull(database.mediaServerDao().byId(1))
        assertEquals("owner-token", tokens.get("owner-server"))
        assertEquals(1L, settings.get(Settings.ActiveProfileId))
    }

    @Test fun adminDeletingOwnServerCompletelyRemovesItsLinkedProfiles() = runTest {
        assertServerAndProfilesRemoved(2)
    }

    @Test fun ownerDeletingAdminServerUsesTheSameCompleteCleanup() = runTest {
        assertServerAndProfilesRemoved(1)
    }

    @Test fun adminCannotDeleteAnotherProfilesServer() = runTest {
        seedRemoval()
        val error = assertFailsWith<ServerRemovalException> { removal().remove(1, null, 2) }
        assertEquals("owner_only", error.code)
        assertEquals(2, database.mediaServerDao().all().size)
        assertEquals(3, database.profileDao().all().size)
    }
}
