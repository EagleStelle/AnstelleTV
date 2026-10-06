package tv.anstelle.core.player

import androidx.media3.common.MimeTypes
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.serializer.toUUID
import tv.anstelle.core.jellyfin.JellyfinClients
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A ready-to-play stream for one item. */
data class ProgramStream(
    val url: String,
    /** Set for HLS, so ExoPlayer doesn't have to guess from the URL. */
    val mimeType: String?,
    val transcoded: Boolean,
    /** Ties the stream to playback reports. Generated locally; no server round trip. */
    val playSessionId: String,
)

/**
 * The `Authorization` header for each server's media requests. Tokens stay out of URLs (and
 * so out of logs and caches); the player's data source adds the header for any request to
 * a registered server, HLS segments included.
 */
@Singleton
class StreamAuth @Inject constructor() {
    private val headers = ConcurrentHashMap<String, String>()

    fun register(baseUrl: String, authorization: String) {
        headers[baseUrl.trimEnd('/')] = authorization
    }

    /** The header for [url], or null when it isn't a server's URL. */
    fun headerFor(url: String): String? {
        for ((base, header) in headers) if (url.startsWith(base)) return header
        return null
    }
}

/**
 * Builds stream URLs locally from the stored server address and item ids: tuning never asks
 * the server for playback info. Direct play streams the file as stored; transcoding uses
 * Jellyfin's HLS playlist, which lists the whole item so the player can start anywhere.
 */
@Singleton
class JellyfinStreams @Inject constructor(
    private val clients: JellyfinClients,
    private val auth: StreamAuth,
) {
    fun stream(baseUrl: String, accessToken: String, itemId: String, mediaSourceId: String?, transcode: Boolean): ProgramStream {
        val api = clients.api(baseUrl, accessToken)
        auth.register(
            baseUrl,
            AuthorizationHeaderBuilder.buildHeader(
                api.clientInfo.name,
                api.clientInfo.version,
                api.deviceInfo.id,
                api.deviceInfo.name,
                accessToken,
            ),
        )
        val playSessionId = UUID.randomUUID().toString().replace("-", "")
        val source = mediaSourceId ?: itemId
        val path = mapOf("itemId" to itemId.toUUID())
        if (!transcode) {
            val url = api.createUrl(
                "/Videos/{itemId}/stream",
                path,
                mapOf("static" to true, "mediaSourceId" to source, "playSessionId" to playSessionId, "deviceId" to api.deviceInfo.id),
                false,
            )
            return ProgramStream(url, mimeType = null, transcoded = false, playSessionId = playSessionId)
        }
        val url = api.createUrl(
            "/Videos/{itemId}/master.m3u8",
            path,
            mapOf(
                "mediaSourceId" to source,
                "playSessionId" to playSessionId,
                "deviceId" to api.deviceInfo.id,
                "videoCodec" to "h264",
                "audioCodec" to "aac",
                "maxAudioChannels" to 2,
                "maxWidth" to MAX_WIDTH,
                "maxHeight" to MAX_HEIGHT,
                "videoBitRate" to VIDEO_BITRATE,
                "audioBitRate" to AUDIO_BITRATE,
                "segmentContainer" to "ts",
                "breakOnNonKeyFrames" to true,
                // Copy what already fits (often the video) instead of encoding it again.
                "allowVideoStreamCopy" to true,
                "allowAudioStreamCopy" to true,
            ),
            false,
        )
        return ProgramStream(url, MimeTypes.APPLICATION_M3U8, transcoded = true, playSessionId = playSessionId)
    }

    private companion object {
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080
        const val VIDEO_BITRATE = 12_000_000
        const val AUDIO_BITRATE = 192_000
    }
}
