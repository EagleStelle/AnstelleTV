package tv.anstelle.webserver

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
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
