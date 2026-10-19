package tv.anstelle.webserver

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import tv.anstelle.core.data.channels.ChannelLogos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerRoutesTest {
    private fun serverTest(block: suspend ApplicationTestBuilder.(TestServer) -> Unit) {
        val server = TestServer()
        testApplication {
            application { anstelleModule(server.deps) }
            block(server)
        }
    }

    /** Runs the pairing flow over HTTP and returns the token cookie value. */
    private suspend fun ApplicationTestBuilder.pair(server: TestServer): String {
        val start = client.post("/api/pair/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"clientName":"Test browser"}""")
        }
        assertEquals(HttpStatusCode.OK, start.status)
        val request = assertNotNull(server.pairing.activeRequest.value)
        val confirm = client.post("/api/pair/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${request.id}","pin":"${request.pin}"}""")
        }
        assertEquals(HttpStatusCode.OK, confirm.status)
        val cookie = assertNotNull(confirm.headers[HttpHeaders.SetCookie])
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        return cookie.substringAfter("$TOKEN_COOKIE=").substringBefore(';')
    }

    @Test
    fun healthIsPublic() = serverTest {
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("AnstelleTV"))
    }

    @Test
    fun serverRemovalUsesThePairedOwnerAndChosenAdmin() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        server.gate.active = FakeGate.ADMIN
        val response = client.delete("/api/servers/1?transferOwnerTo=2") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(FakeGate.OWNER, server.servers.removedBy)
        assertEquals(2L, server.servers.newOwner)
    }

    @Test
    fun serverRemovalReturnsOwnershipConflicts() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        server.servers.removalError = tv.anstelle.core.data.ServerRemovalException("owner_transfer_required", "Choose another admin.")
        val conflict = client.delete("/api/servers/1") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertTrue(conflict.bodyAsText().contains("owner_transfer_required"))
        server.servers.removalError = tv.anstelle.core.data.ServerRemovalException("owner_only", "Only the owner can remove this server.")
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/servers/1") { header(HttpHeaders.Authorization, auth) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/servers/1?transferOwnerTo=bad") { header(HttpHeaders.Authorization, auth) }.status)
    }

    @Test
    fun apiRequiresPairing() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/channels").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/session").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/ws").status)
    }

    @Test
    fun pairingGrantsAccessByCookieAndBearer() = serverTest { server ->
        val token = pair(server)
        val byCookie = client.get("/api/channels") { header(HttpHeaders.Cookie, "$TOKEN_COOKIE=$token") }
        assertEquals(HttpStatusCode.OK, byCookie.status)
        assertTrue(byCookie.bodyAsText().contains("Sitcoms"))

        val byBearer = client.get("/api/session") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, byBearer.status)
        assertTrue(byBearer.bodyAsText().contains("Test browser"))
    }

    @Test
    fun loggingOutRevokesToken() = serverTest { server ->
        val token = pair(server)
        val logout = client.delete("/api/session") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/session") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
        )
    }

    @Test
    fun backupIsAFileForPairedClientsOnly() = serverTest { server ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/backup/export").status)
        // Another device copies it the way it pairs: with the Bearer token, no cookie.
        val token = pair(server)
        val response = client.get("/api/backup/export") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("anstelletv-backup"))
        assertTrue(assertNotNull(response.headers[HttpHeaders.ContentDisposition]).contains("anstelletv-test-tv-2026-10-12.json"))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun wrongPinIsRejected() = serverTest { server ->
        client.post("/api/pair/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"clientName":"x"}""")
        }
        val request = assertNotNull(server.pairing.activeRequest.value)
        val wrong = if (request.pin == "000000") "111111" else "000000"
        val response = client.post("/api/pair/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"requestId":"${request.id}","pin":"$wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("wrong_pin"))
    }

    @Test
    fun crossOriginWritesAreRejected() = serverTest {
        val response = client.post("/api/pair/start") {
            header(HttpHeaders.Origin, "http://evil.example")
            contentType(ContentType.Application.Json)
            setBody("""{"clientName":"x"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun malformedBodyIsBadRequest() = serverTest {
        val response = client.post("/api/pair/start") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun openPairingRoutesRefuseLargeBodies() = serverTest { server ->
        for (route in listOf("/api/pair/start", "/api/pair/confirm")) {
            val response = client.post(route) {
                contentType(ContentType.Application.Json)
                setBody("""{"clientName":"${"x".repeat(PAIR_BODY_BYTES)}"}""")
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }
        assertEquals(null, server.pairing.activeRequest.value)
    }

    @Test
    fun channelLogosUploadServeAndClear() = serverTest { server ->
        val token = pair(server)
        val auth = "Bearer $token"
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/channels/1/logo") { setBody(png) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/channels/1/logo") { header(HttpHeaders.Authorization, auth) }.status)

        val upload = client.put("/api/channels/1/logo") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Image.PNG)
            setBody(png)
        }
        assertEquals(HttpStatusCode.NoContent, upload.status)
        val served = client.get("/api/channels/1/logo?v=1") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, served.status)
        assertEquals(ContentType.Image.PNG, served.contentType())
        assertTrue(served.headers[HttpHeaders.CacheControl]!!.contains("immutable"))
        assertTrue(png.contentEquals(served.readRawBytes()))

        val notImage = client.put("/api/channels/1/logo") {
            header(HttpHeaders.Authorization, auth)
            setBody("hello".toByteArray())
        }
        assertEquals(HttpStatusCode.BadRequest, notImage.status)
        val tooLarge = client.put("/api/channels/1/logo") {
            header(HttpHeaders.Authorization, auth)
            setBody(ByteArray(ChannelLogos.MAX_UPLOAD_BYTES + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/api/channels/9/logo") { header(HttpHeaders.Authorization, auth); setBody(png) }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/api/channels/x/logo") { header(HttpHeaders.Authorization, auth); setBody(png) }.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/channels/1/logo") { header(HttpHeaders.Authorization, auth) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/channels/1/logo") { header(HttpHeaders.Authorization, auth) }.status)
    }

    @Test
    fun libraryChannelLogosCannotBeChanged() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        server.channels.libraryChannel = true

        val upload = client.put("/api/channels/1/logo") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Image.PNG)
            setBody(png)
        }
        assertEquals(HttpStatusCode.Forbidden, upload.status)
        assertTrue(upload.bodyAsText().contains("library_channel_locked"))

        val clear = client.delete("/api/channels/1/logo") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.Forbidden, clear.status)
        assertTrue(clear.bodyAsText().contains("library_channel_locked"))
    }

    @Test
    fun themesListDownloadAndImport() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/themes").status)
        assertTrue(client.get("/api/themes") { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("Rose"))

        val file = client.get("/api/themes/1") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, file.status)
        assertEquals(ContentType.Application.Json, file.contentType()?.withoutParameters())
        assertTrue(file.headers[HttpHeaders.ContentDisposition]!!.contains("rose.json"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/themes/9") { header(HttpHeaders.Authorization, auth) }.status)

        val imported = client.post("/api/themes") {
            header(HttpHeaders.Authorization, auth)
            setBody("""{"key":"x","name":"Mine","colors":{}}""")
        }
        assertEquals(HttpStatusCode.Created, imported.status)
        assertTrue(imported.bodyAsText().contains("Imported"))
        val notTheme = client.post("/api/themes") {
            header(HttpHeaders.Authorization, auth)
            setBody("hello")
        }
        assertEquals(HttpStatusCode.BadRequest, notTheme.status)
        assertTrue(notTheme.bodyAsText().contains("not_a_theme"))
        val tooLarge = client.post("/api/themes") {
            header(HttpHeaders.Authorization, auth)
            setBody(ByteArray(64 * 1024 + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
    }

    @Test
    fun aMemberCannotPair() = serverTest { server ->
        server.gate.active = FakeGate.MEMBER
        val start = client.post("/api/pair/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"clientName":"Test browser"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, start.status)
        assertTrue(start.bodyAsText().contains("no_manager"))
    }

    @Test
    fun aPairingSpeaksForTheProfileThatApprovedIt() = serverTest { server ->
        server.gate.active = FakeGate.ADMIN
        val auth = "Bearer ${pair(server)}"
        val session = client.get("/api/session") { header(HttpHeaders.Authorization, auth) }.bodyAsText()
        assertTrue(session.contains(""""name":"Sam""""))
        assertTrue(session.contains(""""role":"admin""""))
        assertEquals(FakeGate.ADMIN, server.store.clients.values.single().profileId)
    }

    @Test
    fun aProfileNoLongerAnAdminLosesItsBrowsers() = serverTest { server ->
        server.gate.active = FakeGate.ADMIN
        val auth = "Bearer ${pair(server)}"
        assertEquals(HttpStatusCode.OK, client.get("/api/channels") { header(HttpHeaders.Authorization, auth) }.status)
        server.gate.managers.value -= FakeGate.ADMIN
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/channels") { header(HttpHeaders.Authorization, auth) }.status)
    }

    @Test
    fun onlyTheOwnerRestores() = serverTest { server ->
        val file = """{"format":"anstelletv-backup","version":1}"""
        server.gate.active = FakeGate.ADMIN
        val admin = "Bearer ${pair(server)}"
        assertTrue(client.get("/api/backup") { header(HttpHeaders.Authorization, admin) }.bodyAsText().contains(""""canRestore":false"""))
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/backup/restore") { header(HttpHeaders.Authorization, admin); setBody(file) }.status)
        assertTrue(server.backup.restored.isEmpty())

        server.gate.active = FakeGate.OWNER
        val owner = "Bearer ${pair(server)}"
        assertTrue(client.get("/api/backup") { header(HttpHeaders.Authorization, owner) }.bodyAsText().contains(""""canRestore":true"""))
        val check = client.post("/api/backup/check") { header(HttpHeaders.Authorization, owner); setBody(file) }
        assertTrue(check.bodyAsText().contains("Replace everything here?"))
        val notBackup = client.post("/api/backup/check") { header(HttpHeaders.Authorization, owner); setBody("hello") }
        assertEquals(HttpStatusCode.BadRequest, notBackup.status)
        assertTrue(notBackup.bodyAsText().contains("not_a_backup"))
        assertEquals(HttpStatusCode.Accepted, client.post("/api/backup/restore") { header(HttpHeaders.Authorization, owner); setBody(file) }.status)
        assertEquals(1, server.backup.restored.size)
    }

    @Test
    fun libraryChannelsOnlyHideAndFavorite() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        server.channels.libraryChannel = true
        val hide = client.patch("/api/channels/1") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"visible":false}""")
        }
        assertEquals(HttpStatusCode.NoContent, hide.status)
        val rename = client.patch("/api/channels/1") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Mine"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, rename.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/channels/1") { header(HttpHeaders.Authorization, auth) }.status)
        val missing = client.patch("/api/channels/9") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun brokenRulesAreBadRequests() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        val duplicate = client.post("/api/groups") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Sitcoms","start":6000}""")
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)
        assertTrue(duplicate.bodyAsText().contains("A group already has that name."))
        val empty = client.post("/api/channels") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Mine","groupId":1,"lineup":{}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertTrue(empty.bodyAsText().contains("Add a rule or a show first."))
        val banner = client.put("/api/ondemand/banners/home") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"value":7}""")
        }
        assertEquals(HttpStatusCode.BadRequest, banner.status)
    }

    @Test
    fun onDemandBannersAndRowsByTab() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        val settings = client.get("/api/ondemand") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, settings.status)
        assertTrue(settings.bodyAsText().contains("channel:1"))
        val banner = client.put("/api/ondemand/banners/movies") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"value":5}""")
        }
        assertEquals(HttpStatusCode.NoContent, banner.status)
        val rows = client.put("/api/ondemand/rows/movies") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"keys":["channel:1","continue"]}""")
        }
        assertEquals(HttpStatusCode.NoContent, rows.status)
        val noTab = client.put("/api/ondemand/rows/music") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"keys":[]}""")
        }
        assertEquals(HttpStatusCode.NotFound, noTab.status)
    }

    @Test
    fun lineupPreviewDescribesRules() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        val preview = client.post("/api/lineup/preview") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"groups":[{"rules":[{"field":"GENRE","op":"ANY_OF","values":["Comedy"]}]}]}""")
        }
        assertEquals(HttpStatusCode.OK, preview.status)
        val body = preview.bodyAsText()
        assertTrue(body.contains("GENRE Comedy"))
        assertTrue(body.contains(""""episodes":3"""))
        val options = client.get("/api/lineup/options?field=GENRE&op=ANY_OF") { header(HttpHeaders.Authorization, auth) }
        assertTrue(options.bodyAsText().contains("Comedy"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/lineup/options?op=ANY_OF") { header(HttpHeaders.Authorization, auth) }.status)
    }

    @Test
    fun addingAServerSignsInFromTheBrowser() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        server.gate.active = FakeGate.ADMIN
        val found = client.post("/api/servers/find") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"address":"192.168.1.2"}""")
        }
        assertTrue(found.bodyAsText().contains(""""signInId":"s1""""))
        val nowhere = client.post("/api/servers/find") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"address":" "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, nowhere.status)
        assertTrue(nowhere.bodyAsText().contains("look like a server address"))

        val wrong = client.post("/api/servers/signin/s1") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alex","password":"nope"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertTrue(wrong.bodyAsText().contains("Wrong username or password."))
        val right = client.post("/api/servers/signin/s1") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alex","password":"right"}""")
        }
        assertTrue(right.bodyAsText().contains(""""id":1"""))
        assertEquals(FakeGate.OWNER, server.servers.signedInBy)

        val code = client.post("/api/servers/signin/s1/quickconnect") { header(HttpHeaders.Authorization, auth) }
        assertTrue(code.bodyAsText().contains("123456"))
        assertEquals(FakeGate.OWNER, server.servers.quickConnectBy)
        assertTrue(client.get("/api/servers/signin/s1") { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("waiting"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/servers/signin/gone") { header(HttpHeaders.Authorization, auth) }.status)
    }

    @Test
    fun aBrowserCanUnpairAnother() = serverTest { server ->
        val first = "Bearer ${pair(server)}"
        val second = "Bearer ${pair(server)}"
        val secondId = server.store.clients.keys.max()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/clients/$secondId") { header(HttpHeaders.Authorization, first) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/session") { header(HttpHeaders.Authorization, second) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/clients/$secondId") { header(HttpHeaders.Authorization, first) }.status)
    }

    @Test
    fun builtInThemesNeverChange() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        val save = client.put("/api/themes/1/spec") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"spec":{"name":"Rose"}}""")
        }
        assertEquals(HttpStatusCode.Forbidden, save.status)
        assertTrue(save.bodyAsText().contains("built_in_theme"))
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/themes/1") { header(HttpHeaders.Authorization, auth) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/themes/2") { header(HttpHeaders.Authorization, auth) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/themes/1/copy") { header(HttpHeaders.Authorization, auth) }.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/themes/new") { header(HttpHeaders.Authorization, auth) }.status)
    }

    @Test
    fun theBrowserWearsAndPicksItsProfilesTheme() = serverTest { server ->
        server.gate.active = FakeGate.ADMIN
        val auth = "Bearer ${pair(server)}"
        assertTrue(client.get("/api/theme") { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("rose"))
        val use = client.put("/api/theme") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"themeId":2}""")
        }
        assertEquals(HttpStatusCode.NoContent, use.status)
        // The pick is the paired profile's own, not the owner's.
        assertEquals(2L, server.themes.worn[FakeGate.ADMIN])
        assertTrue(client.get("/api/theme") { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("mine"))
        assertTrue(client.get("/api/themes") { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains(""""name":"Mine","isBuiltIn":false,"swatches":[],"inUse":true"""))
        val missing = client.put("/api/theme") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"themeId":9}""")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun theWebUiHasNoProfilePages() = serverTest { server ->
        val auth = "Bearer ${pair(server)}"
        assertEquals(HttpStatusCode.NotFound, client.get("/api/profiles") { header(HttpHeaders.Authorization, auth) }.status)
    }

    @Test
    fun anUnpairedSocketIsLetGo() = serverTest { server ->
        val token = pair(server)
        val sockets = createClient { install(WebSockets) }
        sockets.webSocket("/ws", request = { header(HttpHeaders.Authorization, "Bearer $token") }) {
            val hello = assertIs<Frame.Text>(incoming.receive())
            assertTrue(hello.readText().contains("hello"))
            server.pairing.revoke(server.store.clients.keys.single())
            server.changes.emit(setOf("paired_client"))
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun spaRoutesFallBackToIndex() = serverTest {
        val index = client.get("/configure/channels")
        assertEquals(HttpStatusCode.OK, index.status)
        assertTrue(index.bodyAsText().contains("<title>AnstelleTV</title>"))
        assertEquals("no-cache", index.headers[HttpHeaders.CacheControl])

        val asset = client.get("/app/immutable/app.js")
        assertEquals(HttpStatusCode.OK, asset.status)
        assertTrue(asset.headers[HttpHeaders.CacheControl]!!.contains("immutable"))

        assertEquals(HttpStatusCode.NotFound, client.get("/missing.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/nope").status)
    }
}
