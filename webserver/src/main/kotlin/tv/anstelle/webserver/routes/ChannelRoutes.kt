package tv.anstelle.webserver.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import tv.anstelle.core.data.channels.ChannelLogos
import tv.anstelle.webserver.ServerDependencies
import tv.anstelle.webserver.api.ChannelPatch
import tv.anstelle.webserver.api.CreatedDto
import tv.anstelle.webserver.api.ErrorDto
import tv.anstelle.webserver.api.GroupRequest
import tv.anstelle.webserver.api.LineupDto
import tv.anstelle.webserver.api.MoveRequest
import tv.anstelle.webserver.api.NewChannelRequest
import tv.anstelle.webserver.api.SettingRequest
import java.io.IOException

private const val NO_CHANNEL = "no_channel"
private const val NO_CHANNEL_TEXT = "There is no such channel."
private const val NO_GROUP = "no_group"
private const val NO_GROUP_TEXT = "There is no such group."

/** Channels, their logos and lineups, the lineup builder, groups and the On Demand banner. */
internal fun Route.channelRoutes(deps: ServerDependencies) {
    val api = deps.channels

    route("/channels") {
        get {
            call.requireManager(deps) ?: return@get
            call.respond(api.channels())
        }

        post {
            call.requireManager(deps) ?: return@post
            call.respond(HttpStatusCode.Created, CreatedDto(api.create(call.receive<NewChannelRequest>())))
        }

        route("/{id}") {
            get {
                call.requireManager(deps) ?: return@get
                val id = call.idOrNotFound(NO_CHANNEL, NO_CHANNEL_TEXT) ?: return@get
                call.respond(api.channel(id) ?: return@get call.respondNotFound(NO_CHANNEL, NO_CHANNEL_TEXT))
            }

            patch {
                call.requireManager(deps) ?: return@patch
                val id = call.idOrNotFound(NO_CHANNEL, NO_CHANNEL_TEXT) ?: return@patch
                if (api.update(id, call.receive<ChannelPatch>())) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(NO_CHANNEL, NO_CHANNEL_TEXT)
            }

            delete {
                call.requireManager(deps) ?: return@delete
                val id = call.idOrNotFound(NO_CHANNEL, NO_CHANNEL_TEXT) ?: return@delete
                if (api.delete(id)) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(NO_CHANNEL, NO_CHANNEL_TEXT)
            }

            route("/lineup") {
                get {
                    call.requireManager(deps) ?: return@get
                    val id = call.idOrNotFound(NO_CHANNEL, NO_CHANNEL_TEXT) ?: return@get
                    call.respond(api.lineup(id) ?: return@get call.respondNotFound(NO_CHANNEL, NO_CHANNEL_TEXT))
                }

                put {
                    call.requireManager(deps) ?: return@put
                    val id = call.idOrNotFound(NO_CHANNEL, NO_CHANNEL_TEXT) ?: return@put
                    if (api.setLineup(id, call.receive<LineupDto>())) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(NO_CHANNEL, NO_CHANNEL_TEXT)
                }
            }

            route("/logo") {
                get {
                    call.requireManager(deps) ?: return@get
                    val id = call.idOrNotFound(NO_CHANNEL, NO_CHANNEL_TEXT) ?: return@get
                    val bytes = api.logo(id) ?: return@get call.respondNotFound("no_logo", "This channel has no logo.")
                    // Logo URLs carry a version, so a versioned request never changes.
                    val versioned = call.request.queryParameters["v"] != null
                    call.response.header(HttpHeaders.CacheControl, if (versioned) "private, max-age=31536000, immutable" else "no-cache")
                    // Stored as lossless WebP where the device encodes it, else PNG: the file says which.
                    val webp = bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
                    call.respondBytes(bytes, if (webp) ContentType("image", "webp") else ContentType.Image.PNG)
                }

                put {
                    call.requireManager(deps) ?: return@put
                    val id = call.idOrNotFound(NO_CHANNEL, NO_CHANNEL_TEXT) ?: return@put
                    val bytes = call.receiveBytes(ChannelLogos.MAX_UPLOAD_BYTES, "Logos can be at most ${ChannelLogos.MAX_UPLOAD_BYTES / (1024 * 1024)} MB.") ?: return@put
                    val stored = try {
                        api.setLogo(id, bytes)
                    } catch (e: IOException) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("not_an_image", "That file is not a PNG, JPEG, WebP or GIF image."))
                    }
                    if (stored) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(NO_CHANNEL, NO_CHANNEL_TEXT)
                }

                delete {
                    call.requireManager(deps) ?: return@delete
                    val id = call.idOrNotFound(NO_CHANNEL, NO_CHANNEL_TEXT) ?: return@delete
                    if (api.clearLogo(id)) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(NO_CHANNEL, NO_CHANNEL_TEXT)
                }
            }
        }
    }

    route("/lineup") {
        get("/builder") {
            call.requireManager(deps) ?: return@get
            call.respond(api.builder())
        }

        get("/options") {
            call.requireManager(deps) ?: return@get
            val query = call.request.queryParameters
            val field = query["field"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Say which field."))
            val op = query["op"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Say which comparison."))
            call.respond(api.options(field, op, query["q"].orEmpty(), query["from"]))
        }

        post("/preview") {
            call.requireManager(deps) ?: return@post
            call.respond(api.preview(call.receive<LineupDto>()))
        }
    }

    route("/groups") {
        get {
            call.requireManager(deps) ?: return@get
            call.respond(api.groups())
        }

        post {
            call.requireManager(deps) ?: return@post
            val body = call.receive<GroupRequest>()
            val name = body.name ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid", "Give the group a name."))
            val start = body.start ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid", "Give the group a start."))
            call.respond(HttpStatusCode.Created, CreatedDto(api.createGroup(name, start)))
        }

        patch("/{id}") {
            call.requireManager(deps) ?: return@patch
            val id = call.idOrNotFound(NO_GROUP, NO_GROUP_TEXT) ?: return@patch
            if (api.updateGroup(id, call.receive<GroupRequest>())) call.respond(HttpStatusCode.NoContent) else call.respondNotFound(NO_GROUP, NO_GROUP_TEXT)
        }

        post("/{id}/move") {
            call.requireManager(deps) ?: return@post
            val id = call.idOrNotFound(NO_GROUP, NO_GROUP_TEXT) ?: return@post
            api.moveGroup(id, call.receive<MoveRequest>().delta)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/{id}") {
            call.requireManager(deps) ?: return@delete
            val id = call.idOrNotFound(NO_GROUP, NO_GROUP_TEXT) ?: return@delete
            api.deleteGroup(id)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    put("/settings/ondemand-banner") {
        call.requireManager(deps) ?: return@put
        api.setBannerSize(call.receive<SettingRequest>().value)
        call.respond(HttpStatusCode.NoContent)
    }
}
