package tv.anstelle.webserver

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
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tv.anstelle.core.common.AppInfo
import tv.anstelle.core.data.ThemeImportException
import tv.anstelle.core.data.backup.BackupException
import tv.anstelle.core.data.channels.LibraryChannelLockedException
import tv.anstelle.core.data.sync.userMessage
import tv.anstelle.core.model.ServerConnectException
import tv.anstelle.webserver.api.ApiJson
import tv.anstelle.webserver.api.BackupApi
import tv.anstelle.webserver.api.BuiltInThemeException
import tv.anstelle.webserver.api.ChannelsApi
import tv.anstelle.webserver.api.DeviceApi
import tv.anstelle.webserver.api.ErrorDto
import tv.anstelle.webserver.api.HealthDto
import tv.anstelle.webserver.api.PairConfirmRequest
import tv.anstelle.webserver.api.PairConfirmResponse
import tv.anstelle.webserver.api.PairStartRequest
import tv.anstelle.webserver.api.PairStartResponse
import tv.anstelle.webserver.api.ServersApi
import tv.anstelle.webserver.api.SessionDto
import tv.anstelle.webserver.api.SessionProfileDto
import tv.anstelle.webserver.api.SocketMessage
import tv.anstelle.webserver.api.ThemesApi
import tv.anstelle.webserver.http.StaticFiles
import tv.anstelle.webserver.net.LanGuard
import tv.anstelle.webserver.pairing.PairResult
import tv.anstelle.webserver.pairing.PairStartResult
import tv.anstelle.webserver.pairing.PairingManager
import tv.anstelle.webserver.routes.channelRoutes
import tv.anstelle.webserver.routes.deviceRoutes
import tv.anstelle.webserver.routes.requireManager
import tv.anstelle.webserver.routes.serverRoutes
import tv.anstelle.webserver.routes.themeRoutes
import tv.anstelle.webserver.routes.tokenFromRequest
import kotlin.time.Duration.Companion.seconds

/** Everything the HTTP layer needs. Plain values so routes can run in a JVM test host. */
class ServerDependencies(
    val appInfo: AppInfo,
    val pairing: PairingManager,
    val staticFiles: StaticFiles,
    val channels: ChannelsApi,
    val servers: ServersApi,
    val themes: ThemesApi,
    val backup: BackupApi,
    val device: DeviceApi,
    /** Table names changed in Room, and `sync` while a server syncs, forwarded to browsers over `/ws`. */
    val databaseChanges: Flow<Set<String>>,
)

const val TOKEN_COOKIE = "anstelle_token"
private const val TOKEN_MAX_AGE_SECONDS = 365 * 24 * 60 * 60

/** Changes after which an open socket checks that its pairing still counts. */
private val AccessTables = setOf("paired_client", "profile")

/**
 * The AnstelleTV web server, the owner's and admins' way to manage the TV from a browser:
 * - `/api/health`, `/api/pair/start`, `/api/pair/confirm`: open to the LAN,
 * - every other `/api` route and `/ws`: need a paired token (cookie or Bearer header) that still
 *   speaks for the owner or an admin; another AnstelleTV pairs the same way to copy
 *   `/api/backup/export`,
 * - everything else: the single-page web UI from APK assets.
 */
fun Application.anstelleModule(deps: ServerDependencies) {
    install(ContentNegotiation) { json(ApiJson) }
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
        maxFrameSize = 64 * 1024L
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Malformed request body.")) }
        exception<LibraryChannelLockedException> { call, e -> call.respond(HttpStatusCode.Forbidden, ErrorDto("library_channel_locked", e.message.orEmpty())) }
        exception<BuiltInThemeException> { call, e -> call.respond(HttpStatusCode.Forbidden, ErrorDto("built_in_theme", e.message.orEmpty())) }
        exception<ServerConnectException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto("server", e.userMessage())) }
        exception<ThemeImportException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto("not_a_theme", e.message.orEmpty())) }
        exception<BackupException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto("not_a_backup", e.message.orEmpty())) }
        // Rules the repositories check with `require`: a duplicate group name, an empty lineup, a bad start.
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid", e.message ?: "That isn't allowed.")) }
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
                val body = call.receive<PairStartRequest>()
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
                    PairStartResult.NotAllowed -> call.respond(HttpStatusCode.Forbidden, NotAllowed)
                }
            }

            post("/pair/confirm") {
                val body = call.receive<PairConfirmRequest>()
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
                    PairResult.NotAllowed -> call.respond(HttpStatusCode.Forbidden, NotAllowed)
                }
            }

            get("/session") {
                val (client, manager) = call.requireManager(deps) ?: return@get
                val info = deps.appInfo
                call.respond(
                    SessionDto(
                        clientId = client.id,
                        clientName = client.name,
                        deviceName = info.deviceName,
                        version = info.versionName,
                        isTv = info.isTv,
                        profile = SessionProfileDto(manager.id, manager.name, manager.role.name.lowercase()),
                    ),
                )
            }

            delete("/session") {
                val access = call.requireManager(deps) ?: return@delete
                deps.pairing.revoke(access.client.id)
                call.response.cookies.append(Cookie(name = TOKEN_COOKIE, value = "", maxAge = 0, path = "/", httpOnly = true))
                call.respond(HttpStatusCode.NoContent)
            }

            deviceRoutes(deps)
            serverRoutes(deps)
            channelRoutes(deps)
            themeRoutes(deps)

            route("{...}") {
                handle { call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Unknown API route.")) }
            }
        }

        webSocket("/ws") {
            val token = call.tokenFromRequest()
            val address = call.request.origin.remoteAddress
            if (deps.pairing.authenticate(token, address) == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not paired"))
                return@webSocket
            }
            // A browser unpaired, or whose profile is no longer the owner or an admin, is let go at once.
            suspend fun checkAccess() {
                if (deps.pairing.authenticate(token, address) == null) close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unpaired"))
            }
            // Both listen before the hello goes out, so nothing that changes after it is missed.
            val forward = launch(start = CoroutineStart.UNDISPATCHED) {
                deps.databaseChanges.collect { tables ->
                    send(Frame.Text(ApiJson.encodeToString(SocketMessage.serializer(), SocketMessage("invalidate", tables.sorted()))))
                    if (tables.any { it in AccessTables }) checkAccess()
                }
            }
            val roles = launch(start = CoroutineStart.UNDISPATCHED) {
                deps.pairing.observeManagers().drop(1).collect { checkAccess() }
            }
            send(Frame.Text(ApiJson.encodeToString(SocketMessage.serializer(), SocketMessage("hello"))))
            try {
                // The web UI only sends pings; drain until the browser closes the socket.
                incoming.consumeEach { }
            } finally {
                forward.cancel()
                roles.cancel()
            }
        }

        get("{...}") {
            deps.staticFiles.serve(call, call.request.path())
        }
    }
}

private val NotAllowed = ErrorDto("no_manager", "On the TV, switch to the owner's or an admin's profile, then pair again.")

/** True when there is no Origin header (non-browser clients) or it matches the Host header. */
private fun isSameOrigin(call: ApplicationCall): Boolean {
    val origin = call.request.header(HttpHeaders.Origin) ?: return true
    val host = call.request.header(HttpHeaders.Host) ?: return false
    val originHost = origin.substringAfter("://", missingDelimiterValue = "").trimEnd('/')
    return originHost.equals(host, ignoreCase = true)
}
