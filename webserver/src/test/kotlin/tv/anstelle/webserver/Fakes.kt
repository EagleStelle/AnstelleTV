package tv.anstelle.webserver

import kotlinx.coroutines.flow.MutableSharedFlow
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.ThemeImportException
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.webserver.api.ApiDataSource
import tv.anstelle.webserver.api.ChannelDto
import tv.anstelle.webserver.api.ProfileDto
import tv.anstelle.webserver.api.ThemeDto
import tv.anstelle.webserver.http.AssetSource
import tv.anstelle.webserver.http.StaticFiles
import tv.anstelle.webserver.pairing.PairedClientStore
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

class FakeApi : ApiDataSource {
    /** Logos by channel id; only channel 1 exists. */
    val logos = mutableMapOf<Long, ByteArray>()

    override suspend fun profiles() = listOf(ProfileDto(1, "Home", role = "owner", hasPin = false))
    override suspend fun channels() = listOf(ChannelDto(1, 2, "Sitcoms", null, isHidden = false, isFavorite = true, groupId = null))

    override suspend fun channelLogo(channelId: Long): ByteArray? = logos[channelId]

    override suspend fun setChannelLogo(channelId: Long, bytes: ByteArray): Boolean {
        if (channelId != 1L) return false
        // Stands in for image decoding: PNG files start with 0x89 'P' 'N' 'G'.
        if (bytes.size < 4 || bytes[1] != 'P'.code.toByte()) throw IOException("Not an image")
        logos[channelId] = bytes
        return true
    }

    override suspend fun clearChannelLogo(channelId: Long): Boolean {
        if (channelId != 1L) return false
        logos.remove(channelId)
        return true
    }

    override suspend fun themes() = listOf(ThemeDto(1, "Rose", isBuiltIn = true))

    override suspend fun themeFile(themeId: Long) = if (themeId == 1L) "rose.json" to """{"key":"rose"}""" else null

    /** Stands in for parsing: anything with colors is a theme. */
    override suspend fun importTheme(json: String): ThemeDto {
        if ("\"colors\"" !in json) throw ThemeImportException("That file isn't an AnstelleTV theme")
        return ThemeDto(2, "Imported", isBuiltIn = false)
    }
}

class TestServer(
    val clock: FakeClock = FakeClock(),
    val store: InMemoryClientStore = InMemoryClientStore(),
    val changes: MutableSharedFlow<Set<String>> = MutableSharedFlow(extraBufferCapacity = 8),
) {
    val pairing = PairingManager(store, clock)
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
        api = FakeApi(),
        databaseChanges = changes,
    )
}
