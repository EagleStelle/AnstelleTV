package tv.anstelle.webserver

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ThemeImportException
import tv.anstelle.core.data.backup.BackupException
import tv.anstelle.core.data.backup.BackupFile
import tv.anstelle.core.data.channels.LibraryChannelLockedException
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.core.model.ServerProblem
import tv.anstelle.webserver.api.BackupApi
import tv.anstelle.webserver.api.BackupCheckDto
import tv.anstelle.webserver.api.BackupDto
import tv.anstelle.webserver.api.BuilderDto
import tv.anstelle.webserver.api.BuiltInThemeException
import tv.anstelle.webserver.api.CatalogKindDto
import tv.anstelle.webserver.api.ChannelDetailDto
import tv.anstelle.webserver.api.ChannelDto
import tv.anstelle.webserver.api.ChannelPatch
import tv.anstelle.webserver.api.ChannelsApi
import tv.anstelle.webserver.api.ChannelStatsDto
import tv.anstelle.webserver.api.ChannelsDto
import tv.anstelle.webserver.api.DeviceApi
import tv.anstelle.webserver.api.FoundServerDto
import tv.anstelle.webserver.api.GroupDto
import tv.anstelle.webserver.api.GroupRequest
import tv.anstelle.webserver.api.GroupsDto
import tv.anstelle.webserver.api.KindOverviewDto
import tv.anstelle.webserver.api.LabelledInt
import tv.anstelle.webserver.api.LineupDto
import tv.anstelle.webserver.api.LineupPreviewDto
import tv.anstelle.webserver.api.NewChannelRequest
import tv.anstelle.webserver.api.OnDemandDto
import tv.anstelle.webserver.api.OnDemandOverviewDto
import tv.anstelle.webserver.api.OptionDto
import tv.anstelle.webserver.api.OverviewDto
import tv.anstelle.webserver.api.ReachDto
import tv.anstelle.webserver.api.ServerDetailDto
import tv.anstelle.webserver.api.ServerDto
import tv.anstelle.webserver.api.ServersApi
import tv.anstelle.webserver.api.ServersOverviewDto
import tv.anstelle.webserver.api.ShelfDto
import tv.anstelle.webserver.api.SignInStateDto
import tv.anstelle.webserver.api.ThemeDesignDto
import tv.anstelle.webserver.api.ThemeDto
import tv.anstelle.webserver.api.ThemesApi
import tv.anstelle.webserver.api.WebDto
import tv.anstelle.webserver.api.WornThemeDto
import tv.anstelle.webserver.http.AssetSource
import tv.anstelle.webserver.http.StaticFiles
import tv.anstelle.webserver.pairing.Manager
import tv.anstelle.webserver.pairing.PairedClientStore
import tv.anstelle.webserver.pairing.PairingGate
import tv.anstelle.webserver.pairing.PairingManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPOutputStream

class FakeClock(var now: Long = 1_000_000L) : TimeSource {
    override fun nowUtcMs(): Long = now
}

class InMemoryClientStore : PairedClientStore {
    val clients = mutableMapOf<Long, PairedClientEntity>()
    private var nextId = 1L

    override suspend fun insert(client: PairedClientEntity): Long {
        val id = nextId++
        clients[id] = client.copy(id = id)
        return id
    }

    override suspend fun byTokenHash(tokenHash: String) = clients.values.firstOrNull { it.tokenHash == tokenHash }

    override suspend fun touch(id: Long, seenUtcMs: Long, address: String?) {
        clients[id]?.let { clients[id] = it.copy(lastSeenUtcMs = seenUtcMs, lastAddress = address) }
    }

    override suspend fun delete(id: Long) {
        clients.remove(id)
    }
}

/** Alex is the owner (1) and Sam an admin (2); Kim (3) is a member. Alex is watching. */
class FakeGate : PairingGate {
    var active: Long = OWNER
    val managers = MutableStateFlow(mapOf(OWNER to Manager(OWNER, "Alex", ProfileRole.OWNER), ADMIN to Manager(ADMIN, "Sam", ProfileRole.ADMIN)))

    override suspend fun approver(): Long? = active.takeIf { it in managers.value }
    override suspend fun managers(): Map<Long, Manager> = managers.value
    override fun observeManagers() = managers

    companion object {
        const val OWNER = 1L
        const val ADMIN = 2L
        const val MEMBER = 3L
    }
}

class FakeAssets(private val files: Map<String, String>) : AssetSource {
    /** Every file is "stored gzipped", like the real build output for text files. */
    override val index: Map<String, Boolean> = files.keys.associateWith { true }

    override fun open(path: String, gzipped: Boolean): InputStream {
        val bytes = files.getValue(path).toByteArray()
        if (!gzipped) return ByteArrayInputStream(bytes)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return ByteArrayInputStream(out.toByteArray())
    }
}

/** Only channel 1 exists; groups refuse a second "Sitcoms". */
class FakeChannels : ChannelsApi {
    val logos = mutableMapOf<Long, ByteArray>()
    var libraryChannel = false
    val patches = mutableListOf<ChannelPatch>()

    private fun channel() = ChannelDto(1, 2, "Sitcoms", null, isHidden = false, isFavorite = true, groupId = 1, groupName = "Mine", fromLibrary = libraryChannel)

    private fun custom(channelId: Long): Boolean {
        if (channelId != 1L) return false
        if (libraryChannel) throw LibraryChannelLockedException()
        return true
    }

    override suspend fun channels() = ChannelsDto(listOf(channel()))
    override suspend fun channel(channelId: Long): ChannelDetailDto? = null

    override suspend fun update(channelId: Long, patch: ChannelPatch): Boolean {
        if (channelId != 1L) return false
        if (libraryChannel && (patch.name != null || patch.series != null)) throw LibraryChannelLockedException()
        patches += patch
        return true
    }

    override suspend fun delete(channelId: Long) = custom(channelId)

    override suspend fun create(request: NewChannelRequest): Long {
        require(request.lineup.groups.isNotEmpty() || request.lineup.includes.isNotEmpty()) { "Add a rule or a show first." }
        return 7
    }

    override suspend fun logo(channelId: Long): ByteArray? = logos[channelId]

    override suspend fun setLogo(channelId: Long, bytes: ByteArray): Boolean {
        if (!custom(channelId)) return false
        // Stands in for image decoding: PNG files start with 0x89 'P' 'N' 'G'.
        if (bytes.size < 4 || bytes[1] != 'P'.code.toByte()) throw IOException("Not an image")
        logos[channelId] = bytes
        return true
    }

    override suspend fun clearLogo(channelId: Long): Boolean {
        if (!custom(channelId)) return false
        logos.remove(channelId)
        return true
    }

    override suspend fun lineup(channelId: Long): LineupPreviewDto? = if (custom(channelId)) preview(LineupDto()) else null
    override suspend fun setLineup(channelId: Long, lineup: LineupDto) = custom(channelId)

    /** Stands in for describing: each rule reads as its field and values. */
    override suspend fun preview(lineup: LineupDto) = LineupPreviewDto(
        lineup.copy(groups = lineup.groups.map { group -> group.copy(rules = group.rules.map { it.copy(label = "${it.field} ${it.values.joinToString()}") }) }),
        ReachDto(episodes = 3, movies = 0, videos = 0, series = 1, hours = 1, episodeShare = 0.1f, movieShare = 0f, videoShare = 0f),
    )

    override suspend fun builder() = BuilderDto(emptyList(), emptyList())
    override suspend fun options(field: String, op: String, query: String, from: String?) = listOf(OptionDto("Comedy", "Comedy", 4))

    override suspend fun groups() = GroupsDto(listOf(GroupDto(1, "Sitcoms", 5000, fromLibrary = false, channelCount = 1)), nextStart = 6000)

    override suspend fun createGroup(name: String, start: Int): Long {
        require(!name.equals("Sitcoms", ignoreCase = true)) { "A group already has that name." }
        return 2
    }

    override suspend fun updateGroup(groupId: Long, request: GroupRequest) = groupId == 1L
    override suspend fun moveGroup(groupId: Long, delta: Int) = Unit
    override suspend fun deleteGroup(groupId: Long) = require(groupId != 1L) { "Move or delete its channels first." }

    /** Home and Movies only; Movies opens with Continue watching, then channel 1's row. */
    override suspend fun onDemand() = OnDemandDto(
        homeBanner = 10,
        homeChoices = listOf(LabelledInt(5, "5 channels"), LabelledInt(10, "10 channels")),
        kinds = listOf(
            CatalogKindDto(
                key = "movies",
                label = "Movies",
                banner = 10,
                bannerChoices = listOf(LabelledInt(5, "5 movies"), LabelledInt(10, "10 movies")),
                rows = listOf(ShelfDto("continue", "continue", "Continue watching"), ShelfDto("channel:1", "channel", "Sitcoms", channelNumber = 2)),
            ),
        ),
    )

    override suspend fun setBanner(kind: String, size: Int): Boolean {
        if (kind != "home" && kind != "movies") return false
        require(size in listOf(5, 10)) { "Pick one of 5, 10." }
        return true
    }

    override suspend fun setRows(kind: String, keys: List<String>) = kind == "movies"
}

class FakeServers : ServersApi {
    var state = SignInStateDto("idle")
    var signedInBy: Long? = null
    var quickConnectBy: Long? = null

    override suspend fun servers() = listOf(ServerDto(1, "Home", "http://192.168.1.2:8096", "10.11", "alex", true, null, null, null))
    override suspend fun server(serverId: Long, actorId: Long): ServerDetailDto? = null
    override suspend fun sync(serverId: Long, full: Boolean) = serverId == 1L
    override suspend fun setLibrary(serverId: Long, libraryId: Long, enabled: Boolean) = serverId == 1L && libraryId == 1L
    var removalError: tv.anstelle.core.data.ServerRemovalException? = null
    var removedBy: Long? = null
    var newOwner: Long? = null
    override suspend fun remove(serverId: Long, transferOwnerTo: Long?, actorId: Long): Boolean {
        removalError?.let { throw it }
        removedBy = actorId
        newOwner = transferOwnerTo
        return serverId == 1L
    }

    override suspend fun find(address: String): FoundServerDto {
        if (address.isBlank()) throw ServerConnectException(ServerProblem.INVALID_URL, "Enter a server address")
        return FoundServerDto("s1", "Home", "http://$address:8096", "10.11", quickConnect = true)
    }

    override suspend fun signIn(signInId: String, username: String, password: String, actorId: Long): Long? {
        signedInBy = actorId
        if (signInId != "s1") return null
        if (password != "right") throw ServerConnectException(ServerProblem.BAD_CREDENTIALS, "401")
        return 1
    }

    override suspend fun quickConnect(signInId: String, actorId: Long): String? {
        quickConnectBy = actorId
        return if (signInId == "s1") "123456".also { state = SignInStateDto("waiting", code = it) } else null
    }
    override fun signInState(signInId: String) = state.takeIf { signInId == "s1" }
    override fun cancelSignIn(signInId: String) = Unit
}

/** Theme 1 is built in, theme 2 someone's own; each profile wears theme 1 until it picks another. */
class FakeThemes : ThemesApi {
    val worn = mutableMapOf<Long, Long>()

    override suspend fun themes(profileId: Long) =
        listOf(ThemeDto(1, "Rose", isBuiltIn = true), ThemeDto(2, "Mine", isBuiltIn = false)).map { it.copy(inUse = it.id == (worn[profileId] ?: 1L)) }

    override suspend fun worn(profileId: Long) =
        WornThemeDto(worn[profileId] ?: 1L, buildJsonObject { put("key", if ((worn[profileId] ?: 1L) == 1L) "rose" else "mine") })

    override suspend fun use(profileId: Long, themeId: Long): Boolean {
        if (themeId !in 1L..2L) return false
        worn[profileId] = themeId
        return true
    }
    override suspend fun themeFile(themeId: Long) = if (themeId == 1L) "rose.json" to """{"key":"rose"}""" else null

    /** Stands in for parsing: anything with colors is a theme. */
    override suspend fun import(json: String): ThemeDto {
        if ("\"colors\"" !in json) throw ThemeImportException("That file isn't an AnstelleTV theme")
        return ThemeDto(3, "Imported", isBuiltIn = false)
    }

    override suspend fun create() = 4L
    override suspend fun copy(themeId: Long) = if (themeId in 1L..2L) 5L else null

    override suspend fun delete(themeId: Long): Boolean {
        if (themeId == 1L) throw BuiltInThemeException()
        return themeId == 2L
    }

    override suspend fun design(themeId: Long): ThemeDesignDto? = null

    override suspend fun save(themeId: Long, specJson: String): ThemeDesignDto? {
        if (themeId == 1L) throw BuiltInThemeException()
        return null
    }
}

class FakeBackup : BackupApi {
    val restored = mutableListOf<String>()

    override suspend fun summary(canRestore: Boolean) = BackupDto(1, 2, 1, 0, 1, 0, null, canRestore)
    override suspend fun export() = BackupFile("anstelletv-test-tv-2026-10-12.json", """{"format":"anstelletv-backup","version":1}""")

    override suspend fun check(text: String): BackupCheckDto {
        if ("anstelletv-backup" !in text) throw BackupException("That file isn't an AnstelleTV backup.")
        return BackupCheckDto("Replace everything here?", "1 profile from the file replace what this device holds.")
    }

    override suspend fun restore(text: String) {
        check(text)
        restored += text
    }
}

class FakeDevice(private val store: InMemoryClientStore) : DeviceApi {
    override suspend fun overview() = OverviewDto(
        servers = ServersOverviewDto(1, "Home", needAttention = 0, syncing = 0, lastSyncUtcMs = null, movies = 12, series = 3, episodes = 40),
        channels = ChannelStatsDto(total = 2, custom = 1, library = 1, visible = 2, favorites = 1, hidden = 0, groups = 1),
        onDemand = OnDemandOverviewDto("10 per banner", 10, listOf(KindOverviewDto("movies", "Movies", 10, listOf("Continue watching", "Recently added", "Next up")))),
    )
    override suspend fun web(you: Long) = WebDto(true, listOf("http://192.168.1.20:8765"), emptyList())
    override suspend fun hasClient(clientId: Long) = clientId in store.clients
}

class TestServer(
    val clock: FakeClock = FakeClock(),
    val store: InMemoryClientStore = InMemoryClientStore(),
    val changes: MutableSharedFlow<Set<String>> = MutableSharedFlow(extraBufferCapacity = 8),
) {
    val gate = FakeGate()
    val pairing = PairingManager(store, clock, gate)
    val channels = FakeChannels()
    val servers = FakeServers()
    val themes = FakeThemes()
    val backup = FakeBackup()
    val deps = ServerDependencies(
        appInfo = AppInfo("AnstelleTV", "0.1.0", 1, "Test TV", isTv = true, isDebug = true),
        pairing = pairing,
        staticFiles = StaticFiles(
            FakeAssets(
                mapOf(
                    "index.html" to "<!doctype html><title>AnstelleTV</title>",
                    "app/immutable/app.js" to "console.log('hi')",
                ),
            ),
        ),
        channels = channels,
        servers = servers,
        themes = themes,
        backup = backup,
        device = FakeDevice(store),
        databaseChanges = changes,
    )
}
