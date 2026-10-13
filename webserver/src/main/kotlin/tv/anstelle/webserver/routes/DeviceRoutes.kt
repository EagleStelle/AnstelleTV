package tv.anstelle.webserver.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.webserver.ServerDependencies

private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

/** The TV itself: the home's overview, web control's paired clients, and backups. */
internal fun Route.deviceRoutes(deps: ServerDependencies) {
    get("/overview") {
        call.requireManager(deps) ?: return@get
        call.respond(deps.device.overview())
    }

    get("/web") {
        val access = call.requireManager(deps) ?: return@get
        call.respond(deps.device.web(access.client.id))
    }

    delete("/clients/{id}") {
        call.requireManager(deps) ?: return@delete
        val id = call.idOrNotFound("no_client", "There is no such browser.") ?: return@delete
        if (!deps.device.hasClient(id)) return@delete call.respondNotFound("no_client", "There is no such browser.")
        deps.pairing.revoke(id)
        call.respond(HttpStatusCode.NoContent)
    }

    route("/backup") {
        get {
            val access = call.requireManager(deps) ?: return@get
            call.respond(deps.backup.summary(canRestore = access.manager.role == ProfileRole.OWNER))
        }

        // Everything the device holds, as the file a restore takes: another device copies it, a browser saves it.
        get("/export") {
            call.requireManager(deps) ?: return@get
            val file = deps.backup.export()
            call.attachment(file.name)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(file.json, ContentType.Application.Json)
        }

        post("/check") {
            call.requireOwner(deps) ?: return@post
            val bytes = call.receiveBytes(MAX_BACKUP_BYTES, "That file is too big to be a backup.") ?: return@post
            call.respond(deps.backup.check(bytes.decodeToString()))
        }

        // Replaces every profile, the owner's too, so only the owner restores.
        post("/restore") {
            call.requireOwner(deps) ?: return@post
            val bytes = call.receiveBytes(MAX_BACKUP_BYTES, "That file is too big to be a backup.") ?: return@post
            deps.backup.restore(bytes.decodeToString())
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
