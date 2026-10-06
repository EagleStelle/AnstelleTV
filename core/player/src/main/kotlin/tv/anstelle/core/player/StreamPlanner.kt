package tv.anstelle.core.player

import java.util.Locale

/** How far this device's decoders go for one video codec. */
data class VideoSupport(val maxWidth: Int, val maxHeight: Int, val maxBitDepth: Int)

/**
 * What this device can play, in Jellyfin's codec names ("h264", "hevc", "ac3", …). Built
 * once from `MediaCodecList`, `AudioCapabilities` and the FFmpeg extension by [CapabilityProbe].
 */
data class PlaybackCapabilities(
    val video: Map<String, VideoSupport>,
    /** Audio codecs a platform decoder, the FFmpeg extension or passthrough can handle. */
    val audio: Set<String>,
    /** True when two hardware video decoders can run at once, so the pre-warmed player may decode video too. */
    val concurrentVideoDecoders: Boolean,
)

/** The stream facts synced with each item. */
data class StreamInfo(
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int?,
    val height: Int?,
    val videoBitDepth: Int?,
)

sealed interface StreamDecision {
    /** The file is played as it is, straight from the server. */
    data object DirectPlay : StreamDecision

    /** The server transcodes to HLS; [reasons] say why, for the logs. */
    data class Transcode(val reasons: List<String>) : StreamDecision
}

/**
 * Decides between direct play and Jellyfin's HLS transcoding, locally and without a server
 * call. Direct play needs a container ExoPlayer can read, a video decoder that handles the
 * codec, size and bit depth, and some way to play the audio (a platform decoder, the FFmpeg
 * extension or passthrough).
 */
object StreamPlanner {
    /** Containers ExoPlayer's extractors read, in the names Jellyfin reports. */
    private val Containers = setOf(
        "mkv", "matroska", "webm", "mp4", "m4v", "mov", "3gp", "3g2", "mj2",
        "ts", "mpegts", "m2ts", "mts", "avi", "flv", "mpeg", "mpg", "vob", "ogg", "ogv",
    )

    fun decide(info: StreamInfo, capabilities: PlaybackCapabilities): StreamDecision {
        val reasons = ArrayList<String>(2)
        val containers = info.container?.lowercase(Locale.ROOT)?.split(',')?.map { it.trim() }.orEmpty()
        if (containers.isNotEmpty() && containers.none { it in Containers }) reasons += "container ${info.container}"

        val video = info.videoCodec?.lowercase(Locale.ROOT)
        if (video != null) {
            val support = capabilities.video[video]
            when {
                support == null -> reasons += "video codec $video"
                (info.width ?: 0) > support.maxWidth || (info.height ?: 0) > support.maxHeight ->
                    reasons += "video size ${info.width}x${info.height}"
                (info.videoBitDepth ?: 8) > support.maxBitDepth -> reasons += "video bit depth ${info.videoBitDepth}"
            }
        }

        val audio = info.audioCodec?.lowercase(Locale.ROOT)
        if (audio != null && audioName(audio) !in capabilities.audio) reasons += "audio codec $audio"

        return if (reasons.isEmpty()) StreamDecision.DirectPlay else StreamDecision.Transcode(reasons)
    }

    /** PCM comes in many sample formats ("pcm_s16le", "pcm_s24be", …); all of them are PCM. */
    private fun audioName(codec: String) = if (codec.startsWith("pcm")) "pcm" else codec
}
