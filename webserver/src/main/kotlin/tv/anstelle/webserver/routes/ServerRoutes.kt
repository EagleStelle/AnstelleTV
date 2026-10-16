package tv.anstelle.webserver.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import tv.anstelle.webserver.ServerDependencies
import tv.anstelle.webserver.api.CreatedDto
import tv.anstelle.webserver.api.EnabledRequest
import tv.anstelle.webserver.api.FindServerRequest
import tv.anstelle.webserver.api.PasswordRequest
import tv.anstelle.webserver.api.QuickConnectDto
import tv.anstelle.webserver.api.SyncRequest
import tv.anstelle.webserver.api.ErrorDto
import tv.anstelle.core.data.ServerRemovalException

private const val NO_SERVER = "no_server"
private const val NO_SERVER_TEXT = "There is no such server."
private const val NO_SIGN_IN = "no_sign_in"
private const val NO_SIGN_IN_TEXT = "That took too long. Enter the address again."

/** The Jellyfin servers: state, sync, libraries, removing one and adding one. */
internal fun Route.serverRoutes(deps: ServerDependencies) {
    val api = deps.servers

    route("/servers") {
        get {
            call.requireManager(deps) ?: return@get
            call.respond(api.servers())
        }

        post("/find") {
            call.requireManager(deps) ?: return@post
            call.respond(api.find(call.receive<FindServerRequest>().address))
        }

        route("/signin/{signInId}") {
            get {
                call.requireManager(deps) ?: return@get
                val state = api.signInState(call.parameters["signInId"].orEmpty()) ?: return@get call.respondNotFound(NO_SIGN_IN, NO_SIGN_IN_TEXT)
                call.respond(state)
            }

            post {
                val access = call.requireManager(deps) ?: return@post
                val body = call.receive<PasswordRequest>()
                val serverId = api.signIn(call.parameters["signInId"].orEmpty(), body.username, body.password, access.manager.id)
                    ?: return@post call.respondNotFound(NO_SIGN_IN, NO_SIGN_IN_TEXT)
                call.respond(CreatedDto(serverId))
            }

            post("/quickconnect") {
                val access = call.requireManager(deps) ?: return@post
                val code = api.quickConnect(call.parameters["signInId"].orEmpty(), access.manager.id) ?: return@post call.respondNotFound(NO_SIGN_IN, NO_SIGN_IN_TEXT)
                call.respond(QuickConnectDto(code))
            }

            delete {
                call.requireManager(deps) ?: return@delete
                api.cancelSignIn(call.parameters["signInId"].orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/{id}") {
            get {
                val access = call.requireManager(deps) ?: return@get
                val id = call.idOrNotFound(NO_SERVER, NO_SERVER_TEXT) ?: return@get
                call.respond(api.server(id, access.manager.id) ?: return@get call.respondNotFound(NO_SERVER, NO_SERVER_TEXT))
            }

            delete {
                val access = call.requireManager(deps) ?: return@delete
                val id = call.idOrNotFound(NO_SERVER, NO_SERVER_TEXT) ?: return@delete
                val transfer = call.request.queryParameters["transferOwnerTo"]
                val transferId = transfer?.toLongOrNull()
                if (transfer != null && (transferId == null || transferId <= 0)) {
                    return@delete call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid_owner", "Choose a valid admin."))
                }
                try {
                    if (api.remove(id, transferId, access.manager.id)) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(NO_SERVER, NO_SERVER_TEXT)
                } catch (e: ServerRemovalException) {
                    call.respond(if (e.code == "owner_only") HttpStatusCode.Forbidden else HttpStatusCode.Conflict, ErrorDto(e.code, e.message.orEmpty()))
                }
            }

            post("/sync") {
                call.requireManager(deps) ?: return@post
                val id = call.idOrNotFound(NO_SERVER, NO_SERVER_TEXT) ?: return@post
                if (api.sync(id, call.receive<SyncRequest>().full)) call.respond(HttpStatusCode.Accepted) else call.respondNotFound(NO_SERVER, NO_SERVER_TEXT)
            }

            put("/libraries/{libraryId}") {
                call.requireManager(deps) ?: return@put
                val id = call.idOrNotFound(NO_SERVER, NO_SERVER_TEXT) ?: return@put
                val libraryId = call.idOrNotFound("no_library", "There is no such library.", name = "libraryId") ?: return@put
                if (api.setLibrary(id, libraryId, call.receive<EnabledRequest>().enabled)) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respondNotFound("no_library", "There is no such library.")
                }
            }
        }
    }
}
