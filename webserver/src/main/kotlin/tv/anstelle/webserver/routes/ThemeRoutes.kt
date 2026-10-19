package tv.anstelle.webserver.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import tv.anstelle.webserver.ServerDependencies
import tv.anstelle.webserver.api.CreatedDto
import tv.anstelle.webserver.api.ThemeCheckRequest
import tv.anstelle.webserver.api.ThemeSaveRequest
import tv.anstelle.webserver.api.UseThemeRequest

private const val MAX_THEME_BYTES = 64 * 1024
private const val NO_THEME = "no_theme"
private const val NO_THEME_TEXT = "There is no such theme."

/** The theme library, the designer, and the theme the paired profile (and so the browser) wears. */
internal fun Route.themeRoutes(deps: ServerDependencies) {
    val api = deps.themes

    route("/theme") {
        get {
            val access = call.requireManager(deps) ?: return@get
            call.respond(api.worn(access.manager.id))
        }

        put {
            val access = call.requireManager(deps) ?: return@put
            if (api.use(access.manager.id, call.receive<UseThemeRequest>().themeId)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respondNotFound(NO_THEME, NO_THEME_TEXT)
            }
        }
    }

    route("/themes") {
        get {
            val access = call.requireManager(deps) ?: return@get
            call.respond(api.themes(access.manager.id))
        }

        // A theme file to add: how a TV without a file picker gets one.
        post {
            call.requireManager(deps) ?: return@post
            val bytes = call.receiveBytes(MAX_THEME_BYTES, "A theme file is at most ${MAX_THEME_BYTES / 1024} KB.") ?: return@post
            call.respond(HttpStatusCode.Created, api.import(bytes.decodeToString()))
        }

        post("/new") {
            call.requireManager(deps) ?: return@post
            call.respond(HttpStatusCode.Created, CreatedDto(api.create()))
        }

        route("/{id}") {
            // The theme as a file to save.
            get {
                call.requireManager(deps) ?: return@get
                val id = call.idOrNotFound(NO_THEME, NO_THEME_TEXT) ?: return@get
                val (name, json) = api.themeFile(id) ?: return@get call.respondNotFound(NO_THEME, NO_THEME_TEXT)
                call.attachment(name)
                call.respondText(json, ContentType.Application.Json)
            }

            delete {
                call.requireManager(deps) ?: return@delete
                val id = call.idOrNotFound(NO_THEME, NO_THEME_TEXT) ?: return@delete
                if (api.delete(id)) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(NO_THEME, NO_THEME_TEXT)
            }

            post("/copy") {
                call.requireManager(deps) ?: return@post
                val id = call.idOrNotFound(NO_THEME, NO_THEME_TEXT) ?: return@post
                val copy = api.copy(id) ?: return@post call.respondNotFound(NO_THEME, NO_THEME_TEXT)
                call.respond(HttpStatusCode.Created, CreatedDto(copy))
            }

            get("/spec") {
                call.requireManager(deps) ?: return@get
                val id = call.idOrNotFound(NO_THEME, NO_THEME_TEXT) ?: return@get
                call.respond(api.design(id) ?: return@get call.respondNotFound(NO_THEME, NO_THEME_TEXT))
            }

            put("/spec") {
                call.requireManager(deps) ?: return@put
                val id = call.idOrNotFound(NO_THEME, NO_THEME_TEXT) ?: return@put
                val spec = call.receive<ThemeSaveRequest>().spec
                call.respond(api.save(id, spec.toString()) ?: return@put call.respondNotFound(NO_THEME, NO_THEME_TEXT))
            }

            // Edits not saved yet, read back: readability follows each edit, and a Type or Screen pick is applied here.
            post("/spec/check") {
                call.requireManager(deps) ?: return@post
                val id = call.idOrNotFound(NO_THEME, NO_THEME_TEXT) ?: return@post
                val request = call.receive<ThemeCheckRequest>()
                call.respond(api.check(id, request.spec.toString(), request.choice, request.index) ?: return@post call.respondNotFound(NO_THEME, NO_THEME_TEXT))
            }
        }
    }
}
