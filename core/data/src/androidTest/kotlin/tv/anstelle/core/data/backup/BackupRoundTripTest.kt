package tv.anstelle.core.data.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.channels.ChannelLogos
import tv.anstelle.core.database.AnstelleDatabase
import tv.anstelle.core.database.entity.ChannelDaypartEntity
import tv.anstelle.core.database.entity.ChannelEntity
import tv.anstelle.core.database.entity.ChannelGroupEntity
import tv.anstelle.core.database.entity.ChannelRuleEntity
import tv.anstelle.core.database.entity.ChannelSeasonEntity
import tv.anstelle.core.database.entity.LibraryEntity
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.database.entity.MediaServerEntity
import tv.anstelle.core.database.entity.ProfileAccountEntity
import tv.anstelle.core.database.entity.ProfileEntity
import tv.anstelle.core.database.entity.SettingEntity
import tv.anstelle.core.database.entity.ThemeEntity
import tv.anstelle.core.database.entity.WatchEventEntity
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import tv.anstelle.core.model.SeasonMode
import tv.anstelle.core.model.ThemeColors
import tv.anstelle.core.model.ThemeSpec
import tv.anstelle.core.model.WatchSource
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export, wipe, import: a backup made on one device and restored on another brings back the
 * same backup, against real SQLite with its foreign keys, and history finds its items again
 * after the sync.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logos = ChannelLogos(context)
    private val made = Room.inMemoryDatabaseBuilder(context, AnstelleDatabase::class.java).build()
    private val other = Room.inMemoryDatabaseBuilder(context, AnstelleDatabase::class.java).build()

    @After
    fun close() {
        made.close()
        other.close()
        logos.keepOnly(emptyList())
    }

    private fun item(id: Long, remoteId: String, title: String) = MediaItemEntity(
        id = id, serverId = 3, libraryId = null, remoteId = remoteId, type = MediaType.EPISODE, title = title, sortTitle = null,
        seriesRemoteId = "series-1", showTitle = "Show", season = 1, episode = 1, durationMs = 1_320_000, genres = "|Comedy|", studios = "",
        tags = "", year = 1994, premiereDateUtcMs = null, officialRating = null, ratingScore = null, overview = null, communityRating = null,
        hdrType = HdrType.SDR, audioFormat = null, videoCodec = null, audioCodec = null, container = null, width = null, height = null,
        videoBitDepth = null, audioChannels = null, mediaSourceId = null, tracks = MediaTracks(), audioLanguages = "", primaryImageTag = null,
        backdropImageTag = null, logoImageTag = null, updatedAtUtcMs = 0,
    )

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A device with a server, two profiles, a theme of its own, a library channel and one built by hand, and history. */
    private suspend fun seed(db: AnstelleDatabase) {
        db.mediaServerDao().upsert(MediaServerEntity(3, "jf-home", "Home", "http://192.168.1.20:8096", "u1", "Alex", "12.1.0", 100, lastSyncUtcMs = 200, ownerProfileId = 1))
        db.libraryDao().upsert(LibraryEntity(serverId = 3, remoteId = "lib-shows", name = "Shows", collectionType = "tvshows", isEnabled = true, lastSyncUtcMs = 300))
        db.libraryDao().upsert(LibraryEntity(serverId = 3, remoteId = "lib-music", name = "Music", collectionType = "music", isEnabled = false, lastSyncUtcMs = null))
        db.mediaItemDao().upsertAll(listOf(item(40, "item-a", "Pilot"), item(41, "item-b", "Second")))
        db.themeDao().upsert(ThemeEntity(id = 1, name = "Rose", json = "{}", isBuiltIn = true, builtInKey = "rose", updatedAtUtcMs = 0))
        val custom = db.themeDao().upsert(ThemeEntity(name = "Mine", json = THEME, isBuiltIn = false, builtInKey = null, updatedAtUtcMs = 5))
        db.profileDao().upsert(ProfileEntity(1, "Alex", ProfileRole.OWNER, null, "pbkdf2$20000\$c2FsdA\$aGFzaA", null, custom, 0, 10))
        db.profileDao().upsert(ProfileEntity(2, "Sam", ProfileRole.MEMBER, 12, null, "http://192.168.1.20:8096/Users/u2/Images/Primary", 1, 1, 11))
        db.profileDao().upsertAccount(ProfileAccountEntity(1, 3, "u1", "Alex", "profile-aaaa"))
        db.profileDao().upsertAccount(ProfileAccountEntity(2, 3, "u2", "Sam", "profile-bbbb"))
        db.channelDao().insertGroup(ChannelGroupEntity(id = 4, name = "Genres", sortOrder = 0, numberStart = 3000, libraryKey = "genres"))
        db.channelDao().insertGroup(ChannelGroupEntity(id = 6, name = "Mine", sortOrder = 1, numberStart = 5000))
        val built = ChannelEntity(
            id = 12, number = 5000, name = "Saturday Morning", logoUri = null, kind = ChannelKind.GENERATED, seed = 99,
            seriesOrder = PlaybackOrder.BLOCKS, seriesBlock = 3, movieOrder = PlaybackOrder.EPISODE_ORDER, movieBlock = 1,
            moviesOncePerDay = false, isHidden = false, isFavorite = true, groupId = 6, autoKey = null, createdAtUtcMs = 1, updatedAtUtcMs = 2,
        )
        db.channelDao().insert(built)
        db.channelDao().insert(built.copy(id = 8, number = 3000, name = "Laugh Track", seed = 7, seriesOrder = PlaybackOrder.EPISODE_ORDER, seriesBlock = 1, isFavorite = false, isHidden = true, groupId = 4, autoKey = "tv:genre:comedy"))
        db.channelDao().setLogo(12, logos.store(12, png()), 2)
        val season = db.channelDao().insertSeason(ChannelSeasonEntity(channelId = 12, name = "December", mode = SeasonMode.OVERRIDE_RULES, startMonth = 12, startDay = 1, endMonth = 12, endDay = 31))
        db.channelDao().insertRules(
            listOf(
                ChannelRuleEntity(channelId = 12, seasonId = null, logicGroup = 0, field = RuleField.GENRE, op = RuleOp.ANY_OF, value = "Animation|Family", weight = 1f),
                ChannelRuleEntity(channelId = 12, seasonId = null, logicGroup = -1, field = RuleField.SERIES, op = RuleOp.INCLUDE, value = "series-1", weight = 2f),
                ChannelRuleEntity(channelId = 12, seasonId = season, logicGroup = 0, field = RuleField.TAG, op = RuleOp.ANY_OF, value = "Christmas", weight = 1f),
                ChannelRuleEntity(channelId = 8, seasonId = null, logicGroup = 0, field = RuleField.GENRE, op = RuleOp.ANY_OF, value = "Comedy", weight = 1f),
            ),
        )
        db.channelDao().upsertDaypart(ChannelDaypartEntity(channelId = 12, name = "Mornings", startMinuteOfDay = 360, endMinuteOfDay = 720, maxRatingScore = 7, minRatingScore = null, ruleLogicGroup = null))
        db.settingDao().put(SettingEntity("playback.audio.1", "jpn,eng"))
        db.settingDao().put(SettingEntity("dashboard.2", "false"))
        db.settingDao().put(SettingEntity("ratings.table.3", "12\tPG-13"))
        db.settingDao().put(SettingEntity(Settings.WebServerPort.name, "9000"))
        db.watchEventDao().insertAll(
            listOf(
                WatchEventEntity(profileId = 1, source = WatchSource.CHANNEL, channelId = 12, liveChannelId = null, mediaItemId = 40, title = "Pilot", showTitle = "Show", mediaType = MediaType.EPISODE, startUtcMs = 1_000, durationMs = 60_000, localDay = 20_000, completed = true),
                // Its channel is gone: the time still counts.
                WatchEventEntity(profileId = 1, source = WatchSource.CHANNEL, channelId = 77, liveChannelId = null, mediaItemId = 41, title = "Second", showTitle = "Show", mediaType = MediaType.EPISODE, startUtcMs = 90_000, durationMs = 40_000, localDay = 20_001, completed = false),
                // Sam keeps no dashboard: nothing of it travels.
                WatchEventEntity(profileId = 2, source = WatchSource.ON_DEMAND, channelId = null, liveChannelId = null, mediaItemId = 40, title = "Pilot", showTitle = "Show", mediaType = MediaType.EPISODE, startUtcMs = 5_000, durationMs = 30_000, localDay = 20_000, completed = false),
            ),
        )
    }

    /** What the other device held before: all of it goes, but its own web control port and built-in themes stay. */
    private suspend fun seedOther(db: AnstelleDatabase) {
        db.themeDao().upsert(ThemeEntity(id = 9, name = "Rose", json = "{}", isBuiltIn = true, builtInKey = "rose", updatedAtUtcMs = 0))
        db.mediaServerDao().upsert(MediaServerEntity(1, "jf-elsewhere", "Elsewhere", "http://10.0.0.2:8096", "x", "X", "12.1.0", 0))
        db.profileDao().upsert(ProfileEntity(1, "Someone", ProfileRole.OWNER, null, null, null, null, 0, 0))
        db.channelDao().insertGroup(ChannelGroupEntity(id = 1, name = "Old", sortOrder = 0, numberStart = 100))
        db.channelDao().insert(
            ChannelEntity(1, 100, "Old channel", null, ChannelKind.GENERATED, 1, PlaybackOrder.SHUFFLE, 1, PlaybackOrder.SHUFFLE, 1, true, false, false, 1, null, 0, 0),
        )
        db.settingDao().put(SettingEntity(Settings.WebServerPort.name, "8765"))
        db.settingDao().put(SettingEntity("playback.audio.1", "kor"))
    }

    /** Theme ids are each device's own, and a logo is re-encoded: compare by name and by having one. */
    private fun Backup.comparable() = copy(
        createdAtUtcMs = 0,
        themes = themes.map { it.copy(id = 0) },
        profiles = profiles.map { profile -> profile.copy(customTheme = profile.customTheme?.let { id -> themes.first { it.id == id }.name.hashCode().toLong() }) },
        channels = channels.map { it.copy(logo = it.logo?.let { "png" }) },
    )

    @Test
    fun exportWipeImportBringsBackTheSameBackup() = runTest {
        seed(made)
        val exported = BackupStore(made, made.backupDao(), logos).read(1_791_000_000_000, "0.1.0", "Living Room")
        // Sam's history stays home, and so does this device's web control port.
        assertEquals(listOf(1L, 1L), exported.history.map { it.profileId })
        assertTrue(Settings.WebServerPort.name !in exported.settings)
        assertEquals("item-a", exported.history.first().item)
        assertEquals("rose", exported.profiles.single { it.id == 2L }.builtInTheme)

        // Through the file, as another device receives it.
        val received = BackupFormat.decode(BackupFormat.encode(exported))
        seedOther(other)
        val owner = BackupStore(other, other.backupDao(), logos).write(received, kept = emptyMap())
        assertEquals(1L, owner)

        val restored = BackupStore(other, other.backupDao(), logos).read(1_791_000_000_000, "0.1.0", "Living Room")
        // The channel that was gone drops out of the ranks; everything else is as it was.
        val expected = exported.copy(history = exported.history.map { if (it.channelId == 77L) it.copy(channelId = null) else it })
        assertEquals(expected.comparable(), restored.comparable())

        // The other device's own things: its port stays, its built-in theme takes Sam, the owner is the one watching.
        assertEquals("8765", other.settingDao().get(Settings.WebServerPort.name))
        assertEquals("jpn,eng", other.settingDao().get("playback.audio.1"))
        assertEquals(9L, other.profileDao().byId(2)?.themeId)
        assertEquals("1", other.settingDao().get(Settings.ActiveProfileId.name))
        assertNull(other.mediaServerDao().byJellyfinId("jf-elsewhere"))
        assertEquals(listOf("Laugh Track", "Saturday Morning"), other.channelDao().all().map { it.name }.sorted())
        // Libraries wait for a full fetch; no sign-in was kept, so the server's user is the backup's.
        assertTrue(other.libraryDao().forServer(3).all { it.lastSyncUtcMs == null })
        assertEquals("u1", other.mediaServerDao().byId(3)?.userId)
        val logo = assertNotNull(logos.file(other.channelDao().byId(12)?.logoUri))
        assertEquals(64, BitmapFactory.decodeFile(logo.path).width)

        // The sync brings the library back under new ids; the history finds its items by their Jellyfin ids.
        other.mediaItemDao().upsertAll(listOf(item(500, "item-b", "Second"), item(501, "item-a", "Pilot")))
        assertEquals(2, other.backupDao().settleRestoredItems(3))
        val views = other.backupDao().history(listOf(1L)).associateBy { it.title }
        assertEquals(501L, views.getValue("Pilot").mediaItemId)
        assertEquals(500L, views.getValue("Second").mediaItemId)
        assertTrue(other.backupDao().restoredItems().isEmpty())
    }

    @Test
    fun aKeptSignInKeepsThisDevicesUserAndAddress() = runTest {
        seed(made)
        val exported = BackupStore(made, made.backupDao(), logos).read(0, "0.1.0", "Living Room")
        val here = SignIns.Server("jf-home", "http://jellyfin.home.arpa:8096", "u9", "Alex here", signedIn = true)
        BackupStore(other, other.backupDao(), logos).write(exported, kept = mapOf("jf-home" to here))
        val server = assertNotNull(other.mediaServerDao().byId(3))
        assertEquals("u9", server.userId)
        assertEquals("http://jellyfin.home.arpa:8096", server.baseUrl)
        assertEquals("Home", server.name)
    }

    @Test
    fun aRestoreReplacesWhatAnEarlierRestoreLeft() = runTest {
        seed(made)
        val exported = BackupStore(made, made.backupDao(), logos).read(0, "0.1.0", "Living Room")
        val store = BackupStore(other, other.backupDao(), logos)
        store.write(exported, emptyMap())
        // Restoring again over it (history still waiting for its items) leaves one copy of everything.
        store.write(BackupFormat.decode(BackupFormat.encode(store.read(0, "0.1.0", "Living Room"))), emptyMap())
        assertEquals(2, other.backupDao().history(listOf(1L)).size)
        assertEquals(2, other.backupDao().restoredItems().size)
        assertEquals(1, other.backupDao().themes().count { !it.isBuiltIn })
        // Only the logo of the channel restored last is left.
        val logo = assertNotNull(logos.file(other.channelDao().byId(12)?.logoUri))
        assertEquals(listOf(logo.name), logo.parentFile?.list()?.toList())
    }

    private companion object {
        val THEME = ThemeSpec(
            key = "custom-1",
            name = "Mine",
            colors = ThemeColors(
                background = "#101010", surface = "#181818", surfaceHigh = "#202020", line = "#303030", text = "#F0F0F0",
                textMuted = "#B0B0B0", accent = "#E0457B", onAccent = "#101010", accentContainer = "#402030", onAccentContainer = "#F0F0F0",
                guideCellPast = "#141414", guideRuns = listOf("#242424", "#2A2A2A"),
            ),
        ).toJson()
    }
}
