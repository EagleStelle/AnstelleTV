package tv.anstelle.webserver.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import tv.anstelle.core.model.ProfileRole
import tv.anstelle.webserver.ServerDependencies
import tv.anstelle.webserver.TOKEN_COOKIE
import tv.anstelle.webserver.api.ApiJson
import tv.anstelle.webserver.api.ErrorDto
import tv.anstelle.webserver.pairing.PairingManager

/**
 * Responds 401 and returns null unless the request carries a pairing that still speaks for the
 * owner or an admin.
 */
internal suspend fun ApplicationCall.requireManager(deps: ServerDependencies): PairingManager.Access? {
    val access = deps.pairing.authenticate(tokenFromRequest(), request.origin.remoteAddress)
    if (access == null) respond(HttpStatusCode.Unauthorized, ErrorDto("not_paired", "Pair this browser with the TV first."))
    return access
}

/** Like [requireManager], and responds 403 unless the pairing speaks for the owner. */
internal suspend fun ApplicationCall.requireOwner(deps: ServerDependencies): PairingManager.Access? {
    val access = requireManager(deps) ?: return null
    if (access.manager.role != ProfileRole.OWNER) {
        respond(HttpStatusCode.Forbidden, ErrorDto("owner_only", "Only the owner can do that."))
        return null
    }
    return access
}

internal fun ApplicationCall.tokenFromRequest(): String? {
    val bearer = request.header(HttpHeaders.Authorization)
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
    return bearer ?: request.cookies[TOKEN_COOKIE]
}

/** The path parameter [name] as an id, or null after responding 404 with [error]. */
internal suspend fun ApplicationCall.idOrNotFound(error: String, message: String, name: String = "id"): Long? {
    val id = parameters[name]?.toLongOrNull()
    if (id == null) respondNotFound(error, message)
    return id
}

internal suspend fun ApplicationCall.respondNotFound(error: String, message: String) =
    respond(HttpStatusCode.NotFound, ErrorDto(error, message))

/** The request body, or null after responding 413 when it is over [max] bytes. */
internal suspend fun ApplicationCall.receiveBytes(max: Int, tooLarge: String): ByteArray? {
    val declared = request.contentLength()
    val bytes = if (declared != null && declared > max) null else receiveChannel().readBuffer(max + 1L).readByteArray()
    if (bytes == null || bytes.size > max) {
        respond(HttpStatusCode.PayloadTooLarge, ErrorDto("too_large", tooLarge))
        return null
    }
    return bytes
}

/** The JSON body as [T], or null after responding 413 when it is over [max] bytes. */
internal suspend inline fun <reified T> ApplicationCall.receiveJson(max: Int): T? {
    val bytes = receiveBytes(max, "That request is too big.") ?: return null
    return try {
        ApiJson.decodeFromString<T>(bytes.decodeToString())
    } catch (e: SerializationException) {
        throw BadRequestException("Malformed request body.", e)
    }
}

/** Makes the response a file to save as [fileName]. */
internal fun ApplicationCall.attachment(fileName: String) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString(),
    )
}
