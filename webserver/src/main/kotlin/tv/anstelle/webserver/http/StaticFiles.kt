package tv.anstelle.webserver.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.AppLog
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Serves the single-page web UI. Unknown paths without a file extension fall back to
 * `index.html` so client-side routes like `/configure/channels` work on reload.
 */
class StaticFiles(private val assets: AssetSource) {
    suspend fun serve(call: ApplicationCall, rawPath: String) {
        val index = assets.index
        if (index.isEmpty()) {
            call.respondText(NOT_BUILT_HTML, ContentType.Text.Html.withCharset(Charsets.UTF_8), HttpStatusCode.ServiceUnavailable)
            return
        }
        val path = rawPath.trimStart('/').ifEmpty { INDEX }
        if (path.split('/').any { it == ".." }) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        val target = when {
            index.containsKey(path) -> path
            !path.substringAfterLast('/').contains('.') -> INDEX
            else -> null
        }
        if (target == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val gzipped = index.getValue(target)
        val acceptsGzip = call.request.headers[HttpHeaders.AcceptEncoding]?.contains("gzip") == true
        // Open before sending headers so a missing asset is a clean 404, not a cut-off body.
        val stream = try {
            withContext(Dispatchers.IO) { assets.open(target, gzipped) }
        } catch (e: IOException) {
            AppLog.w(TAG, "Web UI asset missing: $target", e)
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val immutable = target.startsWith(IMMUTABLE_PREFIX)
        call.response.header(
            HttpHeaders.CacheControl,
            if (immutable) "public, max-age=31536000, immutable" else "no-cache",
        )
        call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        call.respond(AssetContent(stream, target, gzipped, sendGzipped = gzipped && acceptsGzip))
    }

    private class AssetContent(
        private val stream: InputStream,
        path: String,
        private val storedGzipped: Boolean,
        private val sendGzipped: Boolean,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = contentTypeFor(path)
        override val headers = if (sendGzipped) {
            io.ktor.http.headersOf(HttpHeaders.ContentEncoding, "gzip")
        } else {
            io.ktor.http.Headers.Empty
        }

        override suspend fun writeTo(channel: ByteWriteChannel) = withContext(Dispatchers.IO) {
            val input = if (storedGzipped && !sendGzipped) GZIPInputStream(stream) else stream
            input.use { source ->
                val out = channel.toOutputStream()
                source.copyTo(out, BUFFER_SIZE)
                out.flush()
            }
        }
    }

    companion object {
        private const val TAG = "WebServer"
        private const val INDEX = "index.html"
        private const val IMMUTABLE_PREFIX = "app/immutable/"
        private const val BUFFER_SIZE = 16 * 1024

        private val NOT_BUILT_HTML = """
            <!doctype html><meta charset="utf-8"><title>AnstelleTV</title>
            <body style="font-family:sans-serif;background:#130C0F;color:#F9F1F4;padding:2rem">
            <h1>AnstelleTV</h1><p>The web UI is not included in this build.
            Install pnpm (or enable corepack) and rebuild the app.</p></body>
        """.trimIndent()

        fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.').lowercase()) {
            "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
            "js", "mjs" -> ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
            "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
            "json", "map" -> ContentType.Application.Json
            "webmanifest" -> ContentType("application", "manifest+json")
            "svg" -> ContentType.Image.SVG
            "png" -> ContentType.Image.PNG
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "webp" -> ContentType("image", "webp")
            "ico" -> ContentType("image", "x-icon")
            "woff2" -> ContentType("font", "woff2")
            "woff" -> ContentType("font", "woff")
            "ttf" -> ContentType("font", "ttf")
            "txt" -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
            else -> ContentType.Application.OctetStream
        }
    }
}
