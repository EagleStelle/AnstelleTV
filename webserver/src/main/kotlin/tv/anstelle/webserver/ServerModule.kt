package tv.anstelle.webserver

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.data.ThemeImportException
import tv.anstelle.core.data.channels.LibraryChannelLockedException
import tv.anstelle.core.data.channels.ChannelLogos
import tv.anstelle.core.database.entity.PairedClientEntity
import tv.anstelle.webserver.api.ApiDataSource
import tv.anstelle.webserver.api.ApiJson
import tv.anstelle.webserver.api.ErrorDto
import tv.anstelle.webserver.api.HealthDto
import tv.anstelle.webserver.api.PairConfirmRequest
import tv.anstelle.webserver.api.PairConfirmResponse
import tv.anstelle.webserver.api.PairStartRequest
import tv.anstelle.webserver.api.PairStartResponse
import tv.anstelle.webserver.api.SessionDto
import tv.anstelle.webserver.api.SocketMessage
import tv.anstelle.webserver.http.StaticFiles
import tv.anstelle.webserver.net.LanGuard
import tv.anstelle.webserver.pairing.PairResult
import tv.anstelle.webserver.pairing.PairStartResult
import tv.anstelle.webserver.pairing.PairingManager
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** Everything the HTTP layer needs. Plain values so routes can run in a JVM test host. */
class ServerDependencies(
    val appInfo: AppInfo,
    val pairing: PairingManager,
    val staticFiles: StaticFiles,
    val api: ApiDataSource,
    /** Table names changed in Room, forwarded to browsers over `/ws`. */
    val databaseChanges: Flow<Set<String>>,
)

const val TOKEN_COOKIE = "anstelle_token"
private const val TOKEN_MAX_AGE_SECONDS = 365 * 24 * 60 * 60
private const val MAX_THEME_BYTES = 64 * 1024

/**
 * The AnstelleTV web server:
 * - `/api/health`, `/api/pair/start`, `/api/pair/confirm`: open to the LAN,
 * - every other `/api` route and `/ws`: need a paired token (cookie or Bearer header); another
 *   AnstelleTV pairs the same way to copy `/api/backup/export`,
 * - everything else: the single-page web UI from APK assets.
 */
fun Application.anstelleModule(deps: ServerDependencies) {
    install(ContentNegotiation) { json(ApiJson) }
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
        maxFrameSize = 64 * 1024L
    }

    // LAN only, and no cross-site writes: mutating requests must come from our own origin.
    intercept(ApplicationCallPipeline.Plugins) {
        if (!LanGuard.isAllowed(call.request.origin.remoteAddress)) {
            call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden", "Only devices on the local network may connect."))
            finish()
            return@intercept
        }
        val isSocket = call.request.path() == "/ws"
        val needsSameOrigin = call.request.httpMethod != HttpMethod.Get || isSocket
        if (needsSameOrigin && !isSameOrigin(call)) {
            call.respond(HttpStatusCode.Forbidden, ErrorDto("bad_origin", "Cross-origin requests are not allowed."))
            finish()
            return@intercept
        }
        // Refuse unpaired sockets before the WebSocket upgrade happens.
        if (isSocket && deps.pairing.authenticate(call.tokenFromRequest(), call.request.origin.remoteAddress) == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("not_paired", "Pair this browser with the TV first."))
            finish()
        }
    }

    routing {
        route("/api") {
            get("/health") {
                val info = deps.appInfo
                call.respond(HealthDto(info.appName, info.versionName, info.deviceName, info.isTv))
            }

            post("/pair/start") {
                val body = call.receiveOrBadRequest<PairStartRequest>() ?: return@post
                when (val result = deps.pairing.start(body.clientName, call.request.origin.remoteAddress, isDevice = body.device)) {
                    is PairStartResult.Started -> call.respond(
                        PairStartResponse(
                            requestId = result.request.id,
                            expiresInSeconds = PairingManager.PIN_TTL_MS / 1000,
                            deviceName = deps.appInfo.deviceName,
                        ),
                    )
                    PairStartResult.RateLimited -> call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorDto("rate_limited", "Too many pairing attempts. Try again in a few minutes."),
                    )
                }
            }

            post("/pair/confirm") {
                val body = call.receiveOrBadRequest<PairConfirmRequest>() ?: return@post
                val result = deps.pairing.confirm(
                    requestId = body.requestId,
                    pin = body.pin,
                    userAgent = call.request.header(HttpHeaders.UserAgent),
                    address = call.request.origin.remoteAddress,
                )
                when (result) {
                    is PairResult.Paired -> {
                        call.response.cookies.append(
                            Cookie(
                                name = TOKEN_COOKIE,
                                value = result.token,
                                maxAge = TOKEN_MAX_AGE_SECONDS,
                                path = "/",
                                httpOnly = true,
                                extensions = mapOf("SameSite" to "Strict"),
                            ),
                        )
                        call.respond(PairConfirmResponse(result.client.id, result.client.name))
                    }
                    PairResult.WrongPin -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("wrong_pin", "That PIN is not correct."))
                    PairResult.TooManyAttempts -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("too_many_attempts", "Too many wrong PINs. Start pairing again."))
                    PairResult.Expired -> call.respond(HttpStatusCode.Gone, ErrorDto("expired", "The PIN expired. Start pairing again."))
                    PairResult.NoRequest -> call.respond(HttpStatusCode.NotFound, ErrorDto("no_request", "No pairing in progress. Start pairing again."))
                }
            }

            get("/session") {
                val client = call.requireClient(deps) ?: return@get
                val info = deps.appInfo
                call.respond(SessionDto(client.id, client.name, info.deviceName, info.versionName))
            }

            delete("/session") {
                val client = call.requireClient(deps) ?: return@delete
                deps.pairing.revoke(client.id)
                call.response.cookies.append(Cookie(name = TOKEN_COOKIE, value = "", maxAge = 0, path = "/", httpOnly = true))
                call.respond(HttpStatusCode.NoContent)
            }

            get("/profiles") {
                call.requireClient(deps) ?: return@get
                call.respond(deps.api.profiles())
            }

            get("/channels") {
                call.requireClient(deps) ?: return@get
                call.respond(deps.api.channels())
            }

            route("/channels/{id}/logo") {
                get {
                    call.requireClient(deps) ?: return@get
                    val id = call.channelIdOrNotFound() ?: return@get
                    val bytes = deps.api.channelLogo(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("no_logo", "This channel has no logo."))
                    // Logo URLs carry a version, so a versioned request never changes.
                    val versioned = call.request.queryParameters["v"] != null
                    call.response.header(HttpHeaders.CacheControl, if (versioned) "private, max-age=31536000, immutable" else "no-cache")
                    // Stored as lossless WebP where the device encodes it, else PNG: the file says which.
                    val webp = bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
                    call.respondBytes(bytes, if (webp) ContentType("image", "webp") else ContentType.Image.PNG)
                }

                put {
                    call.requireClient(deps) ?: return@put
                    val id = call.channelIdOrNotFound() ?: return@put
                    val declared = call.request.contentLength()
                    val bytes = if (declared != null && declared > ChannelLogos.MAX_UPLOAD_BYTES) {
                        null
                    } else {
                        call.receiveChannel().readRemaining(ChannelLogos.MAX_UPLOAD_BYTES + 1L).readByteArray()
                    }
                    if (bytes == null || bytes.size > ChannelLogos.MAX_UPLOAD_BYTES) {
                        call.respond(HttpStatusCode.PayloadTooLarge, ErrorDto("too_large", "Logos can be at most ${ChannelLogos.MAX_UPLOAD_BYTES / (1024 * 1024)} MB."))
                        return@put
                    }
                    val stored = try {
                        deps.api.setChannelLogo(id, bytes)
                    } catch (e: IOException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("not_an_image", "That file is not a PNG, JPEG, WebP or GIF image."))
                        return@put
                    } catch (e: LibraryChannelLockedException) {
                        call.respond(HttpStatusCode.Forbidden, ErrorDto("library_channel_locked", e.message ?: "Library channels cannot be changed."))
                        return@put
                    }
                    if (stored) call.respond(HttpStatusCode.NoContent) else call.respondNoChannel()
                }

                delete {
                    call.requireClient(deps) ?: return@delete
                    val id = call.channelIdOrNotFound() ?: return@delete
                    try {
                        if (deps.api.clearChannelLogo(id)) call.respond(HttpStatusCode.NoContent) else call.respondNoChannel()
                    } catch (e: LibraryChannelLockedException) {
                        call.respond(HttpStatusCode.Forbidden, ErrorDto("library_channel_locked", e.message ?: "Library channels cannot be changed."))
                    }
                }
            }

            get("/themes") {
                call.requireClient(deps) ?: return@get
                call.respond(deps.api.themes())
            }

            // A theme as a file to save, and a theme file to add: how a TV without a file picker trades themes.
            get("/themes/{id}") {
                call.requireClient(deps) ?: return@get
                val file = call.parameters["id"]?.toLongOrNull()?.let { deps.api.themeFile(it) }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("no_theme", "There is no such theme."))
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.first).toString(),
                )
                call.respondText(file.second, ContentType.Application.Json)
            }

            post("/themes") {
                call.requireClient(deps) ?: return@post
                val declared = call.request.contentLength()
                val bytes = if (declared != null && declared > MAX_THEME_BYTES) {
                    null
                } else {
                    call.receiveChannel().readRemaining(MAX_THEME_BYTES + 1L).readByteArray()
                }
                if (bytes == null || bytes.size > MAX_THEME_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ErrorDto("too_large", "A theme file is at most ${MAX_THEME_BYTES / 1024} KB."))
                    return@post
                }
                try {
                    call.respond(HttpStatusCode.Created, deps.api.importTheme(bytes.decodeToString()))
                } catch (e: ThemeImportException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("not_a_theme", e.message ?: "That file isn't an AnstelleTV theme."))
                }
            }

            // Everything the device holds, as the file a restore takes: another device copies it, a browser saves it.
            get("/backup/export") {
                call.requireClient(deps) ?: return@get
                val file = deps.api.backup()
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString(),
                )
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(file.json, ContentType.Application.Json)
            }

            route("{...}") {
                handle { call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Unknown API route.")) }
            }
        }

        webSocket("/ws") {
            val client = deps.pairing.authenticate(call.tokenFromRequest(), call.request.origin.remoteAddress)
            if (client == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not paired"))
                return@webSocket
            }
            send(Frame.Text(ApiJson.encodeToString(SocketMessage.serializer(), SocketMessage("hello"))))
            val forward = launch {
                deps.databaseChanges.collect { tables ->
                    val message = SocketMessage("invalidate", tables.sorted())
                    send(Frame.Text(ApiJson.encodeToString(SocketMessage.serializer(), message)))
                }
            }
            try {
                // The web UI only sends pings; drain until the browser closes the socket.
                incoming.consumeEach { }
            } finally {
                forward.cancel()
            }
        }

        get("{...}") {
            deps.staticFiles.serve(call, call.request.path())
        }
    }
}

/** Parses the JSON body, or responds 400 and returns null when it is malformed. */
private suspend inline fun <reified T : Any> ApplicationCall.receiveOrBadRequest(): T? =
    try {
        receive<T>()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Malformed request body."))
        null
    }

/** The `{id}` path parameter, or null after responding 404 when it isn't a channel id. */
private suspend fun ApplicationCall.channelIdOrNotFound(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) respondNoChannel()
    return id
}

private suspend fun ApplicationCall.respondNoChannel() =
    respond(HttpStatusCode.NotFound, ErrorDto("no_channel", "There is no such channel."))

/** Responds 401 and returns null when the request carries no valid pairing token. */
private suspend fun ApplicationCall.requireClient(deps: ServerDependencies): PairedClientEntity? {
    val client = deps.pairing.authenticate(tokenFromRequest(), request.origin.remoteAddress)
    if (client == null) {
        respond(HttpStatusCode.Unauthorized, ErrorDto("not_paired", "Pair this browser with the TV first."))
    }
    return client
}

private fun ApplicationCall.tokenFromRequest(): String? {
    val bearer = request.header(HttpHeaders.Authorization)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
    return bearer ?: request.cookies[TOKEN_COOKIE]
}

/** True when there is no Origin header (non-browser clients) or it matches the Host header. */
private fun isSameOrigin(call: ApplicationCall): Boolean {
    val origin = call.request.header(HttpHeaders.Origin) ?: return true
    val host = call.request.header(HttpHeaders.Host) ?: return false
    val originHost = origin.substringAfter("://", missingDelimiterValue = "").trimEnd('/')
    return originHost.equals(host, ignoreCase = true)
}
