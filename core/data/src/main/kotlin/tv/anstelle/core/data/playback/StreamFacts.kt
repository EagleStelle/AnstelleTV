package tv.anstelle.core.data.playback

import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.jellyfin.sync.ItemMapper
import tv.anstelle.core.model.HdrType

/**
 * What the player says about the stream on screen: the server it comes from, whether the file
 * plays as it is or Jellyfin transcodes it, and the file's picture and sound.
 */
data class StreamFacts(
    /** The Jellyfin server's name. */
    val server: String,
    /** True when Jellyfin transcodes the file to HLS; false for direct play. */
    val transcoding: Boolean,
    /** "4K HDR10", "1080p", "720p"…; null when the size isn't known. */
    val quality: String?,
    /** "HEVC 10-bit", "H.264", "AV1"…; null when unknown. */
    val video: String?,
    /** "DD+ 5.1", "TrueHD Atmos 7.1", "AAC Stereo"…; null when unknown. */
    val audio: String?,
) {
    /** "Direct Play" or "Transcoding". */
    val method: String get() = if (transcoding) "Transcoding" else "Direct Play"

    companion object {
        fun of(item: MediaItemEntity, serverName: String, transcoding: Boolean) = StreamFacts(
            server = serverName,
            transcoding = transcoding,
            quality = quality(item.width, item.height, item.hdrType),
            video = video(item.videoCodec, item.videoBitDepth),
            audio = audio(item.audioCodec, item.audioFormat, item.audioChannels),
        )

        fun quality(width: Int?, height: Int?, hdr: HdrType): String? {
            val w = width ?: 0
            val h = height ?: 0
            val size = when {
                w <= 0 && h <= 0 -> return null
                w >= 3200 || h >= 2000 -> "4K"
                w >= 1800 || h >= 1000 -> "1080p"
                w >= 1200 || h >= 700 -> "720p"
                h >= 560 -> "576p"
                h >= 440 -> "480p"
                else -> "${h}p"
            }
            val range = when (hdr) {
                HdrType.SDR -> null
                HdrType.HDR10 -> "HDR10"
                HdrType.HDR10_PLUS -> "HDR10+"
                HdrType.HLG -> "HLG"
                HdrType.DOLBY_VISION -> "Dolby Vision"
            }
            return listOfNotNull(size, range).joinToString(" ")
        }

        fun video(codec: String?, bitDepth: Int?): String? {
            val name = when (val c = codec?.lowercase()) {
                null, "" -> return null
                "h264", "avc" -> "H.264"
                "hevc", "h265" -> "HEVC"
                "av1" -> "AV1"
                "vp9" -> "VP9"
                "vp8" -> "VP8"
                "mpeg2video" -> "MPEG-2"
                "mpeg4" -> "MPEG-4"
                "vc1" -> "VC-1"
                else -> c.uppercase()
            }
            return if (bitDepth != null && bitDepth > 8) "$name $bitDepth-bit" else name
        }

        /** The codec's name, Atmos when the track carries it, and the channel layout. */
        fun audio(codec: String?, format: String?, channels: Int?): String? {
            val name = ItemMapper.audioFormat(codec, null)
            val label = when {
                format == "Atmos" -> listOfNotNull(name, "Atmos").joinToString(" ")
                else -> format ?: name
            }
            val layout = when (channels) {
                null, 0 -> null
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                7 -> "6.1"
                8 -> "7.1"
                else -> "$channels ch"
            }
            return listOfNotNull(label, layout).joinToString(" ").ifEmpty { null }
        }
    }
}
