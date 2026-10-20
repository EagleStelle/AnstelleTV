package tv.anstelle.core.player

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.serializer.toUUID
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.jellyfin.JellyfinClients
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.SubtitleCandidate
import tv.anstelle.core.model.SubtitleStream
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.TrackRules
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A subtitle file loaded next to the stream. */
data class SideloadedSubtitle(
    /** Unique within the item, without a colon (libass matches tracks by it). */
    val id: String,
    val url: String,
    val mimeType: String,
    val language: String?,
    val label: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
)

/** A font attachment of the item, for a side-loaded ASS subtitle. */
data class FontRequest(val url: String, val fileName: String)

/** A ready-to-play stream for one item. */
data class ProgramStream(
    val url: String,
    /** Set for HLS, so ExoPlayer doesn't have to guess from the URL. */
    val mimeType: String?,
    val transcoded: Boolean,
    /** Ties the stream to playback reports. Generated locally; no server round trip. */
    val playSessionId: String,
    val subtitles: List<SideloadedSubtitle> = emptyList(),
    /** The item's fonts for libass, when it has ASS subtitles: side-loaded, or in a file that carries them itself. */
    val fonts: List<FontRequest> = emptyList(),
    /** Whether libass may draw this stream's subtitles. */
    val hasAss: Boolean = false,
) {
    fun mediaItem(mediaId: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMimeType(mimeType)
        .setMediaId(mediaId)
        .setSubtitleConfigurations(
            subtitles.map {
                MediaItem.SubtitleConfiguration.Builder(it.url.toUri())
                    .setId(it.id)
                    .setMimeType(it.mimeType)
                    .setLanguage(it.language)
                    .setLabel(it.label)
                    .setSelectionFlags((if (it.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or (if (it.isForced) C.SELECTION_FLAG_FORCED else 0))
                    .build()
            },
        )
        .build()

    /**
     * Whether [next] can be queued behind this stream to follow it without a gap. libass
     * holds one item's subtitles at a time, so an item with ASS is prepared on its own.
     */
    fun chainsWith(next: ProgramStream): Boolean = !hasAss && !next.hasAss
}

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
 *
 * The server sends a transcoded stream with one audio track and no subtitles, so the
 * profile's [TrackPrefs] pick both here: the audio stream is requested by index, and the
 * subtitle (text only) is loaded next to it as a file, ASS as ASS for libass.
 *
 * Streams are opened as the watching profile's Jellyfin user ([accessToken], speaking as
 * [device] when the user is the profile's own), so the server's access rules apply to them.
 */
@Singleton
class JellyfinStreams @Inject constructor(
    private val clients: JellyfinClients,
    private val auth: StreamAuth,
) {
    fun stream(
        baseUrl: String,
        accessToken: String,
        device: String?,
        itemId: String,
        mediaSourceId: String?,
        transcode: Boolean,
        tracks: MediaTracks,
        prefs: TrackPrefs,
    ): ProgramStream {
        val api = clients.api(baseUrl, accessToken, device)
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

        fun subtitle(stream: SubtitleStream): SideloadedSubtitle {
            val (format, mimeType) = if (stream.isAss) "ass" to MimeTypes.TEXT_SSA else "srt" to MimeTypes.APPLICATION_SUBRIP
            val url = api.createUrl(
                "/Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}",
                path + mapOf("mediaSourceId" to source, "index" to stream.index, "format" to format),
                emptyMap(),
                false,
            )
            return SideloadedSubtitle("sub${stream.index}", url, mimeType, stream.language, stream.title, stream.isDefault, stream.isForced)
        }

        fun fonts() = tracks.fonts.map {
            FontRequest(
                api.createUrl("/Videos/{itemId}/{mediaSourceId}/Attachments/{index}", path + mapOf("mediaSourceId" to source, "index" to it.index), emptyMap(), false),
                it.fileName,
            )
        }

        if (!transcode) {
            val url = api.createUrl(
                "/Videos/{itemId}/stream",
                path,
                mapOf("static" to true, "mediaSourceId" to source, "playSessionId" to playSessionId, "deviceId" to api.deviceInfo.id),
                false,
            )
            // Streams inside the file come with it; files next to it are loaded alongside.
            val external = tracks.subtitles.filter { it.isExternal && it.isText }.map(::subtitle)
            return ProgramStream(url, mimeType = null, transcoded = false, playSessionId, external, fonts = if (tracks.hasAss) fonts() else emptyList(), hasAss = tracks.hasAss)
        }

        val audio = TrackRules.pickAudio(tracks.audio, prefs.audioLanguages)
        val audioLanguage = (audio ?: tracks.audio.firstOrNull { it.isDefault } ?: tracks.audio.firstOrNull())?.language
        val texts = tracks.subtitles.filter { it.isText }
        val pick = TrackRules.pickSubtitle(texts.map { SubtitleCandidate(it.language, it.isForced, it.isDefault) }, prefs, audioLanguage)
        val chosen = pick?.let(texts::get)
        val fonts = if (chosen?.isAss == true) fonts() else emptyList()
        val query = buildMap<String, Any> {
            put("mediaSourceId", source)
            put("playSessionId", playSessionId)
            put("deviceId", api.deviceInfo.id)
            audio?.let { put("audioStreamIndex", it.index) }
            put("videoCodec", "h264")
            put("audioCodec", "aac")
            put("maxAudioChannels", 2)
            put("maxWidth", MAX_WIDTH)
            put("maxHeight", MAX_HEIGHT)
            put("videoBitRate", VIDEO_BITRATE)
            put("audioBitRate", AUDIO_BITRATE)
            put("segmentContainer", "ts")
            put("breakOnNonKeyFrames", true)
            // Copy what already fits (often the video) instead of encoding it again.
            put("allowVideoStreamCopy", true)
            put("allowAudioStreamCopy", true)
        }
        val url = api.createUrl("/Videos/{itemId}/master.m3u8", path, query, false)
        AppLog.d(TAG, "HLS tracks: audio ${audio?.let { "${it.index} (${it.language})" } ?: "server default"}, subtitle ${chosen?.let { "${it.index} (${it.language} ${it.codec})" } ?: "none"}, ${fonts.size} fonts")
        return ProgramStream(
            url = url,
            mimeType = MimeTypes.APPLICATION_M3U8,
            transcoded = true,
            playSessionId = playSessionId,
            subtitles = listOfNotNull(chosen?.let(::subtitle)),
            fonts = fonts,
            hasAss = chosen?.isAss == true,
        )
    }

    private companion object {
        const val TAG = "Streams"
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080
        const val VIDEO_BITRATE = 12_000_000
        const val AUDIO_BITRATE = 192_000
    }
}
